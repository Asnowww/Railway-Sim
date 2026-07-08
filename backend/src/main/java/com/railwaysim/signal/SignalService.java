package com.railwaysim.signal;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 信号系统服务 — 控制层核心模块。
 *
 * <p>MA 计算公式：
 * <pre>
 *   MA终点 = min(
 *       前车尾部位置 - safetyGap(120m),
 *       线路终点,
 *       进路终点,
 *       故障区段起点 - safetyGap
 *   )
 *   允许最大速度 = min(线路限速, sqrt(2*a*MA距离), 调度要求速度)
 * </pre>
 *
 * @author 黄旭涛 (信号 + 轨道仿真)
 */
@Service
public class SignalService {

    private static final double DEFAULT_BRAKING_DECELERATION = 0.8;

    /** 从当前区段沿拓扑方向最多预留 N 个区段 */
    private static final int MAX_RESERVE_SEGMENTS = 2;

    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final TrackService trackService;
    private final RouteInterlockingService interlockingService;
    private List<MovementAuthority> authorities = List.of();
    private List<SignalState> signalStates = List.of();

    public SignalService(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        TrackService trackService,
        RouteInterlockingService interlockingService
    ) {
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
        this.trackService = trackService;
        this.interlockingService = interlockingService;
    }

    public synchronized void reset() {
        authorities = List.of();
        signalStates = List.of();
    }

    /**
     * 每 tick 核心：计算 MA + RESERVED + 信号灯色。
     *
     * <p>新增：调用 {@code interlockingService.touchRoutes()} 自动建立/释放进路，
     * 使 MA 的进路冲突约束生效。
     */
    public synchronized void calculateAuthorities(List<TrainState> trains, List<TrackConstraint> trackConstraints,
                                                   List<DispatchConstraint> dispatchConstraints) {
        if (trains.isEmpty()) {
            authorities = List.of();
            signalStates = computeSignalAspects(List.of(), List.of());
            trackService.applyReservations(Set.of());
            return;
        }

        // ① 自动建立/释放进路（联锁触发点）
        interlockingService.touchRoutes(trains);

        Map<String, TrackConstraint> trackByTrain = trackConstraints.stream()
            .collect(Collectors.toMap(TrackConstraint::trainId, Function.identity(), (a, b) -> b));
        Map<String, DispatchConstraint> dispatchByTrain = (dispatchConstraints != null ? dispatchConstraints : List.<DispatchConstraint>of()).stream()
            .collect(Collectors.toMap(DispatchConstraint::trainId, Function.identity(), (a, b) -> b));
        List<TrainState> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparingDouble(TrainState::positionMeters));

        OperationalLineData lineData = infrastructureCatalog.lineData();
        double lineLengthMeters = lineData.lineLengthMeters() > 0
            ? lineData.lineLengthMeters()
            : simulationProperties.getDefaultLineLengthMeters();
        double safetyGap = simulationProperties.getSafetyGapMeters();

        List<MovementAuthority> nextAuthorities = new ArrayList<>();
        Set<String> allReserved = new HashSet<>();

        for (int i = 0; i < ordered.size(); i++) {
            TrainState train = ordered.get(i);
            double trainHead = train.positionMeters();
            TrackSegmentState currentSeg = trackService.segmentAt(trainHead);

            // ② 前车尾部限制
            double nextTrainTailLimit = Double.POSITIVE_INFINITY;
            if (i + 1 < ordered.size()) {
                TrainState nextTrain = ordered.get(i + 1);
                nextTrainTailLimit = nextTrain.positionMeters() - nextTrain.lengthMeters() - safetyGap;
            }

            // ③ 线路终点
            double lineEndLimit = lineLengthMeters;

            // ④ 故障区段限制
            double faultLimit = trackService.nextFaultPosition(trainHead) - safetyGap;

            // ⑤ 进路联锁冲突限制
            double interlockingLimit = interlockingService.maLimitFromRouteConflict(train.id());

            // ⑥ MA 终点取最小值
            double authorityEnd = Math.min(
                Math.min(nextTrainTailLimit, lineEndLimit),
                Math.min(faultLimit, interlockingLimit)
            );
            authorityEnd = Math.max(trainHead, Math.min(authorityEnd, lineLengthMeters));

            // ⑦ 沿拓扑邻居方向收集 RESERVED 区段（而非线性扫描）
            Set<String> reserved = collectTopologyReserved(currentSeg.id(), authorityEnd);
            allReserved.addAll(reserved);

            // ⑧ 计算允许最大速度
            TrackConstraint track = trackByTrain.get(train.id());
            double segmentSpeedLimit = track == null
                ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
                : track.speedLimitMetersPerSecond();

            double maDistance = Math.max(0, authorityEnd - trainHead);
            double safeBrakingSpeed = Math.sqrt(2 * DEFAULT_BRAKING_DECELERATION * maDistance);

            DispatchConstraint dispatch = dispatchByTrain.get(train.id());
            double dispatchLimitedSpeed = dispatch != null
                ? dispatch.applyToSpeedLimit(safeBrakingSpeed)
                : safeBrakingSpeed;

            double speedLimit = Math.min(segmentSpeedLimit, dispatchLimitedSpeed);

            String reason = buildReason(authorityEnd, nextTrainTailLimit, lineEndLimit,
                faultLimit, interlockingLimit, lineLengthMeters);

            nextAuthorities.add(new MovementAuthority(train.id(), authorityEnd, speedLimit, reason));
        }

        trackService.applyReservations(allReserved);
        authorities = List.copyOf(nextAuthorities);
        signalStates = computeSignalAspects(ordered, nextAuthorities);
    }

    // ==================== 信号灯色 ====================

    private List<SignalState> computeSignalAspects(List<TrainState> trains, List<MovementAuthority> authorities) {
        List<TrackSegmentState> trackSegments = trackService.states();
        if (trackSegments.isEmpty()) return List.of();

        List<SignalState> aspects = new ArrayList<>();
        for (TrackSegmentState seg : trackSegments) {
            SignalAspect aspect = switch (seg.occupancy()) {
                case FREE -> SignalAspect.GREEN;
                case RESERVED -> SignalAspect.YELLOW;
                case OCCUPIED, FAULT -> SignalAspect.RED;
            };

            String reasonTrainId = null;
            if (aspect == SignalAspect.RED && seg.occupancy() == TrackOccupancy.OCCUPIED) {
                reasonTrainId = trains.stream()
                    .filter(t -> {
                        double tail = t.positionMeters() - t.lengthMeters();
                        return t.positionMeters() > seg.startMeters() && tail < seg.endMeters();
                    })
                    .map(TrainState::id)
                    .findFirst()
                    .orElse(null);
            }
            if (aspect == SignalAspect.RED && seg.occupancy() == TrackOccupancy.FAULT) {
                reasonTrainId = "FAULT";
            }

            aspects.add(new SignalState(
                "SIG-" + seg.id(), seg.id(), seg.startMeters(), aspect, reasonTrainId
            ));
        }
        return List.copyOf(aspects);
    }

    public synchronized List<MovementAuthority> authorities() {
        return authorities;
    }

    public synchronized List<SignalState> signalStates() {
        return signalStates;
    }

    // ==================== 拓扑驱动的 RESERVED 收集 ====================

    /**
     * 从起始区段沿拓扑方向（forwardNeighbor）走 N 步，收集途中未占用区段。
     *
     * <p>相比之前的线性里程排序，此方法在分叉场景下只会跟一条轨迹（主支），
     * 不会把并行支路上的区段也标 RESERVED。
     *
     * <p>策略：优先取前向邻居中的第一条（主路），若无限速差异则选限速高的。
     * 最多走 {@link #MAX_RESERVE_SEGMENTS} 步，遇到 OCCUPIED/FAULT 即停。
     */
    private Set<String> collectTopologyReserved(String startSegmentId, double maEndMeters) {
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> forwardMap = trackService.forwardNeighborMap();

        String current = startSegmentId;
        int steps = 0;
        while (current != null && steps < MAX_RESERVE_SEGMENTS) {
            List<String> forward = forwardMap.getOrDefault(current, List.of());
            String next;
            if (forward.isEmpty()) {
                break;
            } else if (forward.size() == 1) {
                next = forward.get(0);
            } else {
                // 多个前向邻居 → 选限速最高的（正线）
                String best = forward.get(0);
                double bestSpeed = 0;
                for (String fwdId : forward) {
                    for (TrackSegmentState fwdSeg : trackService.states()) {
                        if (fwdSeg.id().equals(fwdId) && fwdSeg.speedLimitMetersPerSecond() > bestSpeed) {
                            bestSpeed = fwdSeg.speedLimitMetersPerSecond();
                            best = fwdId;
                        }
                    }
                }
                next = best;
            }

            TrackSegmentState seg = null;
            for (TrackSegmentState s : trackService.states()) {
                if (s.id().equals(next)) { seg = s; break; }
            }
            if (seg != null && seg.occupancy() != TrackOccupancy.OCCUPIED
                && seg.occupancy() != TrackOccupancy.FAULT
                && seg.startMeters() < maEndMeters) {
                ids.add(seg.id());
            } else {
                break;
            }

            current = next;
            steps++;
        }
        return ids;
    }

    // ==================== 理由生成 ====================

    private String buildReason(double authorityEnd, double nextTrainLimit, double lineEnd,
                               double faultLimit, double interlockingLimit, double lineLength) {
        if (authorityEnd >= lineEnd || authorityEnd >= lineLength) {
            return "前方区段空闲";
        }
        double closestLimit = Math.min(
            Math.min(Math.min(nextTrainLimit, lineEnd), faultLimit),
            interlockingLimit
        );
        if (closestLimit == faultLimit && faultLimit < Double.POSITIVE_INFINITY) return "故障降级";
        if (closestLimit == interlockingLimit && interlockingLimit < Double.POSITIVE_INFINITY) return "进路冲突";
        if (closestLimit == nextTrainLimit && nextTrainLimit < Double.POSITIVE_INFINITY) return "前车限速";
        return "前方区段空闲";
    }
}
