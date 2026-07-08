package com.railwaysim.signal;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.Comparator;
import com.railwaysim.dispatch.DispatchConstraint;
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
 * <p>职责（按详细设计文档）：
 * <ul>
 *   <li><b>闭塞管理</b>：固定闭塞 + 移动闭塞(CBTC简化)</li>
 *   <li><b>进路联锁</b>：三条核心规则（道岔-信号-进路互锁）</li>
 *   <li><b>MA 计算</b>：前车尾部 - safetyGap → MA终点 → 限速值</li>
 *   <li><b>信号状态</b>：红/黄/绿三色状态机</li>
 *   <li><b>故障安全</b>：故障注入 → 信号降红灯 → MA缩短 → 告警</li>
 *   <li><b>RESERVED 预留</b>：MA覆盖但车未到的区段标为 RESERVED</li>
 * </ul>
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

    /** 默认制动减速度 (m/s²)，用于从 MA 距离反推安全速度 */
    private static final double DEFAULT_BRAKING_DECELERATION = 0.8;

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

    // ==================== 生命周期 ====================

    public synchronized void reset() {
        authorities = List.of();
        signalStates = List.of();
    }

    // ==================== MA 计算核心 ====================

    /**
     * 计算每列车的移动授权(MA)，标记 RESERVED 区段，计算信号灯色。
     *
     * <p>算法步骤：
     * <ol>
     *   <li>列车按位置升序排列</li>
     *   <li>对每列车，计算 MA 终点 = min(前车尾部-safetyGap, 线路终点, 故障点前)</li>
     *   <li>MA 覆盖的未占用区段 → applyReservations 到 TrackService</li>
     *   <li>计算信号灯色：前方区段 FREE→GREEN, RESERVED→YELLOW, OCCUPIED/FAULT→RED</li>
     * </ol>
     *
     * @param trains           当前所有列车状态
     * @param trackConstraints 轨道约束(限速/坡度等) per train
     */
    public synchronized void calculateAuthorities(List<TrainState> trains, List<TrackConstraint> trackConstraints,
                                                   List<DispatchConstraint> dispatchConstraints) {
        if (trains.isEmpty()) {
            authorities = List.of();
            signalStates = computeSignalAspects(List.of(), List.of());
            trackService.applyReservations(Set.of());
            return;
        }

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

            // ---- 计算各项 MA 限制 ----
            // ① 前车尾部限制
            double nextTrainTailLimit = Double.POSITIVE_INFINITY;
            if (i + 1 < ordered.size()) {
                TrainState nextTrain = ordered.get(i + 1);
                nextTrainTailLimit = nextTrain.positionMeters() - nextTrain.lengthMeters() - safetyGap;
            }

            // ② 线路终点
            double lineEndLimit = lineLengthMeters;

            // ③ 故障区段限制（故障—安全）
            double faultLimit = trackService.nextFaultPosition(trainHead) - safetyGap;

            // ④ 进路联锁冲突限制
            double interlockingLimit = interlockingService.maLimitFromRouteConflict(train.id());

            // ⑤ MA 终点取最小值
            double authorityEnd = Math.min(
                Math.min(nextTrainTailLimit, lineEndLimit),
                Math.min(faultLimit, interlockingLimit)
            );
            authorityEnd = Math.max(trainHead, Math.min(authorityEnd, lineLengthMeters));

            // ---- 收集 RESERVED 区段 ----
            Set<String> reserved = collectSegmentsInRange(trainHead, authorityEnd);
            allReserved.addAll(reserved);

            // ---- 计算允许最大速度 ----
            TrackConstraint track = trackByTrain.get(train.id());
            double segmentSpeedLimit = track == null
                ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
                : track.speedLimitMetersPerSecond();

            // 从 MA 距离反推安全速度: v_max = sqrt(2 * a * (MA_end - position))
            double maDistance = Math.max(0, authorityEnd - trainHead);
            double safeBrakingSpeed = Math.sqrt(2 * DEFAULT_BRAKING_DECELERATION * maDistance);

            // 调度限速约束
            DispatchConstraint dispatch = dispatchByTrain.get(train.id());
            double dispatchLimitedSpeed = dispatch != null
                ? dispatch.applyToSpeedLimit(safeBrakingSpeed)
                : safeBrakingSpeed;

            double speedLimit = Math.min(segmentSpeedLimit, dispatchLimitedSpeed);

            // ---- reason 字段 ----
            String reason = buildReason(authorityEnd, nextTrainTailLimit, lineEndLimit,
                faultLimit, interlockingLimit, lineLengthMeters);

            nextAuthorities.add(new MovementAuthority(train.id(), authorityEnd, speedLimit, reason));
        }

        // 原子更新：先写 RESERVED 再算信号灯色（灯色需要读到 RESERVED 状态）
        trackService.applyReservations(allReserved);
        authorities = List.copyOf(nextAuthorities);
        signalStates = computeSignalAspects(ordered, nextAuthorities);
    }

    // ==================== 信号灯色计算 ====================

    /**
     * 为每个区段入口生成一个虚拟信号机，计算灯色。
     *
     * <p>规则：
     * <ul>
     *   <li>前方区段 OCCUPIED 或 FAULT → RED</li>
     *   <li>前方区段 RESERVED → YELLOW</li>
     *   <li>前方区段 FREE → GREEN</li>
     * </ul>
     *
     * <p>有列车而信号为 RED 时，附上原因列车 ID。
     */
    private List<SignalState> computeSignalAspects(List<TrainState> trains, List<MovementAuthority> authorities) {
        List<TrackSegmentState> trackSegments = trackService.states();
        if (trackSegments.isEmpty()) {
            return List.of();
        }

        List<SignalState> aspects = new ArrayList<>();
        for (TrackSegmentState seg : trackSegments) {
            SignalAspect aspect = switch (seg.occupancy()) {
                case FREE -> SignalAspect.GREEN;
                case RESERVED -> SignalAspect.YELLOW;
                case OCCUPIED, FAULT -> SignalAspect.RED;
            };

            // 找距离最近的占用该区段的列车
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
                "SIG-" + seg.id(),
                seg.id(),
                seg.startMeters(),
                aspect,
                reasonTrainId
            ));
        }
        return List.copyOf(aspects);
    }

    // ==================== 查询接口 ====================

    public synchronized List<MovementAuthority> authorities() {
        return authorities;
    }

    public synchronized List<SignalState> signalStates() {
        return signalStates;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 收集 [fromMeters, toMeters) 范围内前 MAX_LOOKAHEAD 个区段的 ID。
     *
     * <p>一个列车不需要把 MA 全范围都标 RESERVED——只需预留紧邻前方的区段，
     * 防止后车同时抢同一段轨道。类比 MESI 缓存协议：只 lock 即将写入的 cache line，
     * 不锁整个地址空间。
     *
     * <p>具体策略：从列车当前所在区段开始，沿里程递增方向取前 N 个 FREE 区段，
     * 最多不超过 MA 终点。
     */
    private static final int MAX_RESERVE_SEGMENTS = 2;

    private Set<String> collectSegmentsInRange(double fromMeters, double toMeters) {
        Set<String> ids = new HashSet<>();
        List<TrackSegmentState> ordered = trackService.states().stream()
            .sorted(Comparator.comparingDouble(TrackSegmentState::startMeters))
            .toList();

        int count = 0;
        for (TrackSegmentState seg : ordered) {
            if (count >= MAX_RESERVE_SEGMENTS) break;
            if (seg.startMeters() >= toMeters) break;
            if (seg.endMeters() <= fromMeters) continue;
            if (seg.occupancy() == TrackOccupancy.OCCUPIED || seg.occupancy() == TrackOccupancy.FAULT) continue;
            ids.add(seg.id());
            count++;
        }
        return ids;
    }

    /**
     * 生成 MA 的 reason 字符串，说明 MA 被哪个因素截断。
     */
    private String buildReason(double authorityEnd, double nextTrainLimit, double lineEnd,
                               double faultLimit, double interlockingLimit, double lineLength) {
        if (authorityEnd >= lineEnd || authorityEnd >= lineLength) {
            return "前方区段空闲";
        }
        double closestLimit = Math.min(
            Math.min(Math.min(nextTrainLimit, lineEnd), faultLimit),
            interlockingLimit
        );
        if (closestLimit == faultLimit) {
            return "故障降级";
        }
        if (closestLimit == interlockingLimit) {
            return "进路冲突";
        }
        if (closestLimit == nextTrainLimit) {
            return "前车限速";
        }
        return "前方区段空闲";
    }
}
