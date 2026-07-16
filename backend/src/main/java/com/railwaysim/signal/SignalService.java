package com.railwaysim.signal;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SignalService {

    private static final Logger log = LoggerFactory.getLogger(SignalService.class);
    private static final double DEFAULT_BRAKING_DECELERATION = 0.8;
    private static final int MAX_RESERVE_SEGMENTS = 2;
    private static final double STATION_STOP_WINDOW_METERS = 25.0;

    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final TrackService trackService;
    private final RouteInterlockingService interlockingService;
    private final FixedBlockAuthorityCalculator fixedBlockCalculator;
    private List<MovementAuthority> authorities = List.of();
    private List<SignalState> signalStates = List.of();

    /** 每列车在站台已停 tick 数（用于停站时间控制） */
    private final Map<String, Integer> stationDwellTicks = new LinkedHashMap<>();
    /** 每列车同一站停站完成后的释放锁存，防止刚释放又被同一站二次截停。 */
    private final Set<String> releasedStationStops = new HashSet<>();
    /** 每列车当前是否在站台停车中 */
    private final Map<String, Boolean> atStationStop = new LinkedHashMap<>();
    /** 默认站台停站秒数（仅在没有调度停站时间时使用） */
    private static final int DEFAULT_DWELL_SECONDS = 5;

    public SignalService(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        TrackService trackService,
        RouteInterlockingService interlockingService,
        FixedBlockAuthorityCalculator fixedBlockCalculator
    ) {
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
        this.trackService = trackService;
        this.interlockingService = interlockingService;
        this.fixedBlockCalculator = fixedBlockCalculator;
    }

    public synchronized void reset() {
        authorities = List.of();
        signalStates = List.of();
        stationDwellTicks.clear();
        releasedStationStops.clear();
        atStationStop.clear();
    }

    public synchronized void calculateAuthorities(
        List<TrainState> trains,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints
    ) {
        if (trains.isEmpty()) {
            authorities = List.of();
            signalStates = computeSignalAspects(List.of());
            trackService.applyReservations(Set.of());
            return;
        }

        interlockingService.touchRoutes(trains);

        Map<String, TrackConstraint> trackByTrain = trackConstraints.stream()
            .collect(Collectors.toMap(TrackConstraint::trainId, Function.identity(), (a, b) -> b));
        Map<String, DispatchConstraint> dispatchByTrain =
            (dispatchConstraints == null ? List.<DispatchConstraint>of() : dispatchConstraints).stream()
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

        boolean isFixedBlock = "FIXED".equalsIgnoreCase(simulationProperties.getBlockMode());
        if (isFixedBlock && !ordered.isEmpty()) {
            var fixedResult = fixedBlockCalculator.calculate(
                ordered, trackByTrain, dispatchByTrain, lineLengthMeters, safetyGap);

            // ---- 固定闭塞后处理：叠加进路联锁 + 站台停靠约束 ----
            Map<String, MovementAuthority> fixedByTrain = fixedResult.authorities().stream()
                .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (a, b) -> b));
            List<MovementAuthority> constrained = new ArrayList<>();
            for (TrainState train : ordered) {
                MovementAuthority orig = fixedByTrain.get(train.id());
                if (orig == null) continue;

                double constrainedEnd = orig.authorityEndMeters();
                double constrainedSpeed = orig.speedLimitMetersPerSecond();
                String reason = orig.reason();
                String endSegId = orig.endSegmentId();
                String reasonCode = orig.reasonCode();

                // 进路联锁：等待进路建立时 MA 不得前伸
                if (interlockingService.isRouteHoldActive(train.id())) {
                    constrainedEnd = train.positionMeters();
                    constrainedSpeed = 0;
                    reason = "等待进路建立(" + interlockingService.routeHoldReason(train.id()) + ")";
                    reasonCode = "ROUTE_CONFLICT";
                }

                // 站台停靠：MA 截到站台位置
                DispatchConstraint dispatch = dispatchByTrain.get(train.id());
                Map<String, Double> stationLimits = resolveStationDwell(train, dispatch);
                if (stationLimits.containsKey("maEndAt")
                    && stationLimits.get("maEndAt") < constrainedEnd) {
                    constrainedEnd = stationLimits.get("maEndAt");
                    boolean dwelling = stationLimits.getOrDefault("isDwelling", 0.0) > 0;
                    if (dwelling) {
                        constrainedSpeed = 0;
                        int dwellElapsedSec = stationLimits.getOrDefault("dwellElapsedSec", 0.0).intValue();
                        int dwellTargetSec = stationLimits.getOrDefault("dwellTargetSec", (double) DEFAULT_DWELL_SECONDS).intValue();
                        reason = "站台停靠(" + dwellElapsedSec + "/" + dwellTargetSec + "s)";
                    } else {
                        double maDistance = Math.max(0, constrainedEnd - train.positionMeters());
                        double safeBrakingSpeed = Math.sqrt(2 * DEFAULT_BRAKING_DECELERATION * maDistance);
                        double dispatchLimitedSpeed = dispatch == null
                            ? safeBrakingSpeed
                            : dispatch.applyToSpeedLimit(safeBrakingSpeed);
                        constrainedSpeed = Math.min(constrainedSpeed, dispatchLimitedSpeed);
                        reason = "站台停靠";
                    }
                    reasonCode = "STATION_DWELL";
                }

                constrainedEnd = Math.max(train.positionMeters(), constrainedEnd);
                if (constrainedEnd <= train.positionMeters()) {
                    constrainedSpeed = 0;
                }
                TrackSegmentState endSeg = trackService.segmentAt(constrainedEnd);
                if (endSeg != null) endSegId = endSeg.id();

                constrained.add(new MovementAuthority(train.id(), constrainedEnd, constrainedSpeed,
                    reason, orig.currentSegmentId(), endSegId, reasonCode));
            }

            // 信号释放站台后通知车辆层: 已释放站台的车辆别刹停
            // 信号释放站台后通知车辆层: 已释放站台的车辆别刹停(站距→大值)
            for (int j = 0; j < trackConstraints.size(); j++) {
                TrackConstraint tc = trackConstraints.get(j);
                if (releasedStationStops.stream().anyMatch(k -> k.startsWith(tc.trainId() + ":"))) {
                    trackConstraints.set(j, new TrackConstraint(
                        tc.trainId(), tc.segmentId(), tc.speedLimitMetersPerSecond(),
                        tc.gradient(), tc.curveRadiusMeters(), 9999.0));
                }
            }

            trackService.applyReservations(fixedResult.reservedSegmentIds());
            authorities = List.copyOf(constrained);
            signalStates = computeSignalAspects(ordered);
            return;
        }

        for (int i = 0; i < ordered.size(); i++) {
            TrainState train = ordered.get(i);
            double trainHead = train.positionMeters();

            // ---- 故障安全：刹车/牵引失效 → 加大安全距离 ----
            double effectiveSafetyGap = resolveSafetyGap(train);

            // 与占用染色同源：前端车辆车道、进路匹配、预留起点全部跟随列车绑定的区段
            TrackSegmentState currentSeg = trackService.segmentForTrain(train);

            double nextTrainTailLimit = Double.POSITIVE_INFINITY;
            if (i + 1 < ordered.size()) {
                TrainState nextTrain = ordered.get(i + 1);
                // 异轨列车不构成前后阻挡（环线里程 up/down 域不重叠，此为双保险）
                TrackSegmentState nextSeg = trackService.segmentForTrain(nextTrain);
                boolean differentTrack = currentSeg != null && nextSeg != null
                    && currentSeg.track() != null && nextSeg.track() != null
                    && !currentSeg.track().equals(nextSeg.track());
                if (!differentTrack) {
                    // 终点站已停列车不再阻挡后车: 到终点的车视为"已清出线路"
                    String nextTrack = nextSeg != null ? nextSeg.track() : null;
                    boolean nextCleared = trackService.isAtTerminalStation(nextTrain)
                        || (nextTrain.zeroSpeed()
                            && nextTrain.positionMeters() >= trackService.trackEndMeters(nextTrack) - 10);
                    if (nextCleared) {
                        nextTrainTailLimit = Double.POSITIVE_INFINITY;
                    } else {
                        double linearLimit = nextTrain.positionMeters() - nextTrain.lengthMeters() - effectiveSafetyGap;
                        nextTrainTailLimit = linearLimit;
                    }
                }
            }
            // 拓扑感知（移动闭塞）：沿本车路径查找前方障碍
            if ("MOVING".equalsIgnoreCase(simulationProperties.getBlockMode())) {
                double topoObstacle = resolveTopologyObstacle(train, effectiveSafetyGap);
                if (topoObstacle < nextTrainTailLimit) {
                    nextTrainTailLimit = topoObstacle;
                }
            }

            // MA 端点按本车股道里程域截断（环线里程下 up 车不得越过 L 冲进 down 域）
            double trackEndLimit = trackService.trackEndMeters(currentSeg != null ? currentSeg.track() : null);
            double lineEndLimit = trackEndLimit;
            double faultLimit = trackService.nextFaultPosition(trainHead) - effectiveSafetyGap;
            boolean waitingForRoute = interlockingService.isRouteHoldActive(train.id());
            double interlockingLimit = waitingForRoute
                ? trainHead
                : interlockingService.maLimitFromRouteConflict(train.id());

            DispatchConstraint dispatch = dispatchByTrain.get(train.id());

            // ---- 停站控制：MA终点收到站台位置前，等停站时间到才延伸 ----
            Map<String, Double> stationLimits = resolveStationDwell(train, dispatch);
            if (stationLimits.containsKey("maEndAt")) {
                lineEndLimit = Math.min(lineEndLimit, stationLimits.get("maEndAt"));
            }

            double authorityEnd = Math.min(
                Math.min(nextTrainTailLimit, lineEndLimit),
                Math.min(faultLimit, interlockingLimit)
            );
            authorityEnd = Math.max(trainHead, Math.min(authorityEnd, trackEndLimit));

            allReserved.addAll(collectTopologyReserved(train.id(), currentSeg.id(), authorityEnd));

            TrackConstraint track = trackByTrain.get(train.id());
            double segmentSpeedLimit = track == null
                ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
                : track.speedLimitMetersPerSecond();

            double maDistance = Math.max(0, authorityEnd - trainHead);
            double safeBrakingSpeed = Math.sqrt(2 * DEFAULT_BRAKING_DECELERATION * maDistance);

            double dispatchLimitedSpeed = dispatch == null
                ? safeBrakingSpeed
                : dispatch.applyToSpeedLimit(safeBrakingSpeed);

            // 如已在站台停靠中且 MA 已到站台位置，速度应降到 0
            if (stationLimits.containsKey("isDwelling") && stationLimits.get("isDwelling") > 0) {
                dispatchLimitedSpeed = 0;
            }

            double speedLimit = Math.min(segmentSpeedLimit, dispatchLimitedSpeed);
            String reason = buildReason(authorityEnd, nextTrainTailLimit, lineEndLimit,
                faultLimit, interlockingLimit, trackEndLimit);
            if (stationLimits.containsKey("isDwelling") && stationLimits.get("isDwelling") > 0) {
                int dwellElapsedSec = stationLimits.getOrDefault("dwellElapsedSec", 0.0).intValue();
                int dwellTargetSec = stationLimits.getOrDefault("dwellTargetSec", (double) DEFAULT_DWELL_SECONDS).intValue();
                reason = "站台停靠(" + dwellElapsedSec + "/" + dwellTargetSec + "s)";
            }
            if (waitingForRoute) {
                reason = "等待进路建立(" + interlockingService.routeHoldReason(train.id()) + ")";
            }

            String segId = currentSeg != null ? currentSeg.id() : "?";
            TrackSegmentState endSeg = trackService.segmentAt(authorityEnd);
            String endSegId = endSeg != null ? endSeg.id() : segId;
            String reasonCode = deriveReasonCode(reason);
            nextAuthorities.add(new MovementAuthority(train.id(), authorityEnd, speedLimit,
                reason, segId, endSegId, reasonCode));
        }

        // 信号释放站台后通知车辆层: 已释放站台的车辆别刹停(站距→大值)
        for (int i = 0; i < trackConstraints.size(); i++) {
            TrackConstraint tc = trackConstraints.get(i);
            if (releasedStationStops.stream().anyMatch(k -> k.startsWith(tc.trainId() + ":"))) {
                trackConstraints.set(i, new TrackConstraint(
                    tc.trainId(), tc.segmentId(), tc.speedLimitMetersPerSecond(),
                    tc.gradient(), tc.curveRadiusMeters(), 9999.0));
            }
        }

        trackService.applyReservations(allReserved);
        authorities = List.copyOf(nextAuthorities);
        signalStates = computeSignalAspects(ordered);
    }

    public synchronized List<MovementAuthority> authorities() {
        return authorities;
    }

    public synchronized List<SignalState> signalStates() {
        return signalStates;
    }

    /**
     * 信号门控：列车在站台+零速+进路建立 → 允许开门。
     */
    public synchronized List<String> doorOpenAllowedTrainIds() {
        return atStationStop.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .toList();
    }

    /** 基于当前区段状态和列车位置重新计算信号灯色（不重新算MA）。 */
    public synchronized void recomputeSignalAspects() {
        signalStates = computeSignalAspects(List.of());
    }

    private List<SignalState> computeSignalAspects(List<TrainState> trains) {
        List<TrackSegmentState> trackSegments = trackService.states();
        if (trackSegments.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> forwardMap = trackService.forwardNeighborMap();
        Map<String, TrackSegmentState> segmentById = trackSegments.stream()
            .collect(Collectors.toMap(TrackSegmentState::id, Function.identity()));
        List<OperationalLineData.SignalDefinition> configuredSignals = infrastructureCatalog.lineData().signals();

        // 正式线路使用基础设施信号表中的真实 ID、所属区段和公里标。
        // 仅为没有信号表的旧测试/兼容线路按区段入口合成信号，避免改变既有无配置场景。
        record SignalPlacement(String id, String segmentId, double positionMeters) {}
        List<SignalPlacement> placements = configuredSignals.isEmpty()
            ? trackSegments.stream()
                .map(segment -> new SignalPlacement("SIG-" + segment.id(), segment.id(), segment.startMeters()))
                .toList()
            : configuredSignals.stream()
                .map(signal -> new SignalPlacement(signal.id(), signal.segmentId(), signal.positionMeters()))
                .toList();

        List<SignalState> aspects = new ArrayList<>();
        for (SignalPlacement placement : placements) {
            TrackSegmentState seg = segmentById.get(placement.segmentId());
            if (seg == null) {
                throw new IllegalStateException(
                    "Signal " + placement.id() + " references unknown segment " + placement.segmentId()
                );
            }
            // 按"前方两区段"判断灯色：
            //   首区段 OCCUPIED/FAULT → RED
            //   首区段 FREE/RESERVED + 第二区段 OCCUPIED/FAULT/不存在 → YELLOW
            //   首区段 FREE/RESERVED + 第二区段 FREE/RESERVED → GREEN

            List<String> forward = forwardMap.getOrDefault(seg.id(), List.of());
            TrackSegmentState seg1 = seg; // 首保护区段（本区段）
            TrackSegmentState seg2 = forward.isEmpty() ? null
                : findSegment(forward.get(0)); // 第二保护区段

            SignalAspect aspect;
            String reasonTrainId = null;

            if (seg1.occupancy() == TrackOccupancy.OCCUPIED
                || seg1.occupancy() == TrackOccupancy.FAULT) {
                aspect = SignalAspect.RED;
                if (seg1.occupancy() == TrackOccupancy.OCCUPIED) {
                    reasonTrainId = trains.stream()
                        .filter(t -> {
                            double tail = t.positionMeters() - t.lengthMeters();
                            return t.positionMeters() > seg1.startMeters()
                                && tail < seg1.endMeters();
                        })
                        .map(TrainState::id)
                        .findFirst()
                        .orElse(null);
                } else {
                    reasonTrainId = "FAULT";
                }
            } else if (seg1.occupancy() == TrackOccupancy.RESERVED) {
                aspect = SignalAspect.YELLOW;
            } else if (seg2 != null && (seg2.occupancy() == TrackOccupancy.OCCUPIED
                || seg2.occupancy() == TrackOccupancy.FAULT)) {
                aspect = SignalAspect.YELLOW;
            } else {
                aspect = SignalAspect.GREEN;
            }

            aspects.add(new SignalState(
                placement.id(),
                placement.segmentId(),
                placement.positionMeters(),
                aspect,
                reasonTrainId
            ));
        }
        return List.copyOf(aspects);
    }

    private Set<String> collectTopologyReserved(String trainId, String startSegmentId, double maEndMeters) {
        List<String> routePath = interlockingService.establishedSegmentPathForTrain(trainId);
        if (!routePath.isEmpty()) {
            return collectReservedAlongRoute(routePath, startSegmentId, maEndMeters);
        }
        return collectReservedAlongActiveTopology(startSegmentId, maEndMeters);
    }

    private Set<String> collectReservedAlongRoute(List<String> routePath, String startSegmentId, double maEndMeters) {
        Set<String> ids = new HashSet<>();
        int currentIndex = routePath.indexOf(startSegmentId);
        if (currentIndex < 0) {
            return ids;
        }
        int steps = 0;
        for (int i = currentIndex + 1; i < routePath.size() && steps < MAX_RESERVE_SEGMENTS; i++) {
            TrackSegmentState seg = findSegment(routePath.get(i));
            if (seg == null) break;
            if (seg.occupancy() == TrackOccupancy.OCCUPIED || seg.occupancy() == TrackOccupancy.FAULT) break;
            // 第一个前向段总是预留（不管MA覆盖多远，下一个段需要准备）
            // 后续段只在MA范围内预留
            if (steps > 0 && seg.startMeters() >= maEndMeters) break;
            ids.add(seg.id());
            steps++;
        }
        return ids;
    }

    private Set<String> collectReservedAlongActiveTopology(String startSegmentId, double maEndMeters) {
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> forwardMap = trackService.kmForwardMap();
        TrackSegmentState startSeg = findSegment(startSegmentId);
        String track = startSeg != null ? startSeg.track() : "up";
        String current = startSegmentId;
        int steps = 0;
        while (current != null && steps < MAX_RESERVE_SEGMENTS) {
            List<String> forward = forwardMap.getOrDefault(current, List.of());
            if (forward.isEmpty()) {
                break;
            }

            String next = chooseActiveForwardNeighbor(current, forward, track);
            TrackSegmentState seg = findSegment(next);
            if (seg == null || seg.startMeters() >= maEndMeters) {
                break;
            }
            String nextTrack = seg.track() != null ? seg.track() : "up";
            // 渡线/异轨不预留——预留严格限制在本车股道内，任何跨轨即停
            if (!nextTrack.equals(track)) {
                break;
            }
            if (seg.occupancy() == TrackOccupancy.OCCUPIED || seg.occupancy() == TrackOccupancy.FAULT) {
                break;
            }

            ids.add(seg.id());
            current = next;
            steps++;
        }
        return ids;
    }

    private String chooseActiveForwardNeighbor(String currentSegmentId, List<String> forward) {
        return chooseActiveForwardNeighbor(currentSegmentId, forward, null);
    }

    private String chooseActiveForwardNeighbor(String currentSegmentId, List<String> forward, String preferredTrack) {
        // 1. Prefer the LOCKED switch-activated branch.
        for (SwitchState sw : trackService.switchStates()) {
            if (sw.locked() && forward.contains(sw.activeSegmentId())) {
                return sw.activeSegmentId();
            }
        }

        // 2. Prefer same-track neighbor (avoid skipping to crossover/other direction)
        if (preferredTrack != null) {
            for (String fwdId : forward) {
                TrackSegmentState fwdSeg = findSegment(fwdId);
                if (fwdSeg != null && preferredTrack.equals(fwdSeg.track())) {
                    return fwdId;
                }
            }
        }

        // 3. Fallback: pick the forward neighbor with higher speed limit (main track)
        String best = forward.get(0);
        double bestSpeed = -1;
        for (String fwdId : forward) {
            TrackSegmentState fwdSeg = findSegment(fwdId);
            if (fwdSeg != null && fwdSeg.speedLimitMetersPerSecond() > bestSpeed) {
                bestSpeed = fwdSeg.speedLimitMetersPerSecond();
                best = fwdId;
            }
        }
        return best;
    }

    /**
     * 沿拓扑前向邻居搜索本车前方最近的障碍点（前车尾部减安全距离）。
     * 在分叉场景下沿当前激活的道岔方向搜索，不会跨越到平行支路。
     */
    private double resolveTopologyObstacle(TrainState self, double safetyGap) {
        TrackSegmentState seg = trackService.segmentForTrain(self);
        if (seg == null) return Double.POSITIVE_INFINITY;
        Map<String, List<String>> forwardMap = trackService.kmForwardMap();
        String selfTrack = seg.track() != null ? seg.track() : "main";
        String current = seg.id();
        int steps = 0;

        while (current != null && steps < 20) { // 搜索上限：20段
            TrackSegmentState curSeg = findSegment(current);
            if (curSeg == null) break;
            String curTrack = curSeg.track() != null ? curSeg.track() : "main";
            // 不同轨道路径分叉：停止搜索，不沿渡线/异轨邻居继续（主线不被上行/支线阻挡）
            if (!curTrack.equals(selfTrack) && !"main".equals(curTrack)) {
                return Double.POSITIVE_INFINITY;
            }
            // 发现此区段上有别的列车→取尾部减安全间隔
            if (curSeg.occupancy() == TrackOccupancy.OCCUPIED) {
                // 检查是否是自己的区段
                if (self.positionMeters() >= curSeg.startMeters()
                    && self.positionMeters() < curSeg.endMeters()) {
                    // 自己所在区段，继续向下找
                } else {
                    return curSeg.startMeters() - safetyGap;
                }
            }
            if (curSeg.occupancy() == TrackOccupancy.FAULT) {
                return curSeg.startMeters() - safetyGap;
            }
            List<String> forward = forwardMap.getOrDefault(current, List.of());
            if (forward.isEmpty()) break;
            current = forward.size() == 1 ? forward.get(0) : chooseActiveForwardNeighbor(current, forward);
            steps++;
        }
        return Double.POSITIVE_INFINITY;
    }

    private TrackSegmentState findSegment(String id) {
        for (TrackSegmentState segment : trackService.states()) {
            if (segment.id().equals(id)) {
                return segment;
            }
        }
        return null;
    }

    // ==================== 停站控制 ====================

    /**
     * 信号决定停站时机：列车接近站台 → MA 收到站台位置 → 零速稳定后累计站停 ticks。
     * 站停时间到（默认 25 ticks = 5s）后 MA 延伸至下一区段，列车可发车。
     *
     * @return Map with "maEndAt"(截断MA终点) and/or "isDwelling"(>0 表示正在站停)
     */
    private Map<String, Double> resolveStationDwell(TrainState train, DispatchConstraint dispatch) {
        Map<String, Double> result = new LinkedHashMap<>();
        OperationalLineData lineData = infrastructureCatalog.lineData();
        List<OperationalLineData.StationDefinition> stations = lineData.stations();
        if (stations.isEmpty()) return result;

        double head = train.positionMeters();
        boolean isDown = "DOWN".equalsIgnoreCase(train.direction());
        double lineLen = infrastructureCatalog.lineData().lineLengthMeters();
        // DOWN列车: effectiveHead = L-head → 物理递增镜像为递减,匹配S113→S101站序
        double searchHead = (isDown && lineLen > 0) ? lineLen - head : head;
        TrackSegmentState currentSegment = trackService.segmentForTrain(train);
        String track = currentSegment == null ? null : currentSegment.track();

        // 释放标记只在列车尚未驶离当前站台窗口时有效；否则会把后续站距永久屏蔽为 9999m。
        String releasePrefix = train.id() + ":";
        releasedStationStops.removeIf(key -> {
            if (!key.startsWith(releasePrefix)) return false;
            String stationId = key.substring(releasePrefix.length());
            return stations.stream()
                .filter(station -> station.id().equals(stationId))
                .findFirst()
                .map(station -> head > lineData.stopPointMeters(station, track) + STATION_STOP_WINDOW_METERS)
                .orElse(true);
        });

        // 找车头前方的下一站；允许车头在站中心后方 10m 内继续被视为站停窗口。
        // 环线里程下 up/down 站点各在自己的里程域（S1xx ≤ L < S2xx），统一按里程递增搜索。
        OperationalLineData.StationDefinition nextStation = stations.stream()
            .filter(s -> s.centerMeters() >= searchHead - STATION_STOP_WINDOW_METERS)
            .min(Comparator.comparingDouble(s -> s.centerMeters() - searchHead))
            .orElse(null);
        if (nextStation == null) return result;
        double stopPoint = lineData.stopPointMeters(nextStation, track);

        String dwellKey = train.id() + ":" + nextStation.id();
        if (dispatch != null && dispatch.releaseStationStop()) {
            stationDwellTicks.remove(dwellKey);
            releasedStationStops.add(dwellKey);
            atStationStop.remove(train.id());
            log.info(
                "[DispatchLoop] signal released station stop train={} station={} commandIds={} reason={}",
                train.id(),
                nextStation.id(),
                dispatch.sourceCommandIds(),
                dispatch.reason()
            );
            return result;
        }

        boolean stopped = Math.abs(head - stopPoint) <= STATION_STOP_WINDOW_METERS && train.zeroSpeed();
        if (releasedStationStops.contains(dwellKey)) {
            // 已释放的站在驶离窗口前保持放行；驶离后的标记已在方法入口清理。
            atStationStop.remove(train.id());
            return result; // 不截断MA, 让列车自由通过
        }
        int tickCount = stationDwellTicks.getOrDefault(dwellKey, 0);

        if (stopped) {
            stationDwellTicks.put(dwellKey, tickCount + 1);
            atStationStop.put(train.id(), true);
            if (tickCount == 0) {
                log.info(
                    "[DispatchLoop] signal latched station dwell train={} station={} position={}m",
                    train.id(),
                    nextStation.id(),
                    String.format("%.1f", head)
                );
            }
        }

        int dwellTicks = stationDwellTicks.getOrDefault(dwellKey, 0);
        int targetDwellTicks = targetDwellTicks();
        // 调度停站调整：deltaDwellSec正→延长, 负→缩短（本次生效）
        if (dispatch != null && dispatch.deltaDwellSec() != null) {
            int delta = dispatch.deltaDwellSec();
            long tickMillis = Math.max(1, simulationProperties.getTickMillis());
            int deltaTicks = Math.max(0, (int) Math.ceil(Math.abs(delta) * 1000.0 / tickMillis));
            targetDwellTicks = delta >= 0 ? targetDwellTicks + deltaTicks : Math.max(1, targetDwellTicks - deltaTicks);
        }
        int dwellElapsedSec = (int) Math.floor(dwellTicks * simulationProperties.getTickMillis() / 1000.0);

        // 站停未完成 → MA 截到停车点后窗口边缘（余量供 ATO 精确对位，越过即越权）
        if (dwellTicks < targetDwellTicks) {
            double stationMaEnd = nextStation.centerMeters() + STATION_STOP_WINDOW_METERS;
            // M9长段保护：列车距离站台超过安全制动距离时不截MA，避免过早刹车卡死在站窗外
            double distanceToStation = nextStation.centerMeters() - head;
            if (distanceToStation > 0 && train.speedMetersPerSecond() > 2.0) {
                double safeBraking = (train.speedMetersPerSecond() * train.speedMetersPerSecond())
                    / (2 * DEFAULT_BRAKING_DECELERATION) + 120.0;
                if (distanceToStation > safeBraking) {
                    return result; // 高速且离得远，不截MA
                }
            }
            // 兜底：MA至少给60m或2倍车长，避免零速卡死
            if (stationMaEnd < head + Math.max(60.0, train.lengthMeters() * 2)) {
                stationMaEnd = head + Math.max(60.0, train.lengthMeters() * 2);
            }
            result.put("maEndAt", stationMaEnd);
            if (dwellTicks > 0) {
                result.put("isDwelling", 1.0);
                result.put("dwellElapsedSec", (double) dwellElapsedSec);
                result.put("dwellTargetSec", (double) DEFAULT_DWELL_SECONDS);
            }
            return result;
        }

        // 站停完成 → 释放 MA，清除计数
        stationDwellTicks.remove(dwellKey);
        releasedStationStops.add(dwellKey);
        atStationStop.remove(train.id());
        log.info(
            "[DispatchLoop] signal scheduled dwell complete train={} station={} elapsed={}s target={}s",
            train.id(),
            nextStation.id(),
            dwellElapsedSec,
            DEFAULT_DWELL_SECONDS
        );
        return result;
    }

    private int targetDwellTicks() {
        long tickMillis = Math.max(1, simulationProperties.getTickMillis());
        return Math.max(1, (int) Math.ceil(DEFAULT_DWELL_SECONDS * 1000.0 / tickMillis));
    }

    // ==================== 故障安全距离 ====================

    /**
     * 刹车失效/牵引失效 → 制动能力下降 → 需要更长安全距离。
     * 正常: 120m, 部分失效(>=1/3): 180m, 严重(>=2/3): 300m.
     */
    private double resolveSafetyGap(TrainState train) {
        double base = simulationProperties.getSafetyGapMeters();
        if (base <= 0) base = 120;
        // totalCount=0 means no data yet — default to normal
        // 严重故障(faultLevel>=2): 2.5x
        if (train.faultLevel() >= 2) return base * 2.5;
        // 部分失效: 1.5x
        if (!train.brakeAvailable() && !train.tractionAvailable()) return base * 2.0;
        if (!train.brakeAvailable() || !train.tractionAvailable()) return base * 1.5;
        return base;
    }

    private static String deriveReasonCode(String reason) {
        if (reason == null) return "NORMAL";
        if (reason.contains("故障")) return "FAULT_LIMIT";
        if (reason.contains("前车")) return "TRAIN_AHEAD";
        if (reason.contains("进路")) return "ROUTE_CONFLICT";
        if (reason.contains("站台")) return "STATION_DWELL";
        return "NORMAL";
    }

    private String buildReason(double authorityEnd, double nextTrainLimit, double lineEnd,
                               double faultLimit, double interlockingLimit, double lineLength) {
        if (authorityEnd >= lineEnd || authorityEnd >= lineLength) {
            return "前方区段空闲";
        }
        double closestLimit = Math.min(
            Math.min(Math.min(nextTrainLimit, lineEnd), faultLimit),
            interlockingLimit
        );
        if (closestLimit == faultLimit && faultLimit < Double.POSITIVE_INFINITY) {
            return "故障降级";
        }
        if (closestLimit == interlockingLimit && interlockingLimit < Double.POSITIVE_INFINITY) {
            return "进路冲突";
        }
        if (closestLimit == nextTrainLimit && nextTrainLimit < Double.POSITIVE_INFINITY) {
            return "前车限速";
        }
        return "前方区段空闲";
    }
}
