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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SignalService {

    private static final double DEFAULT_BRAKING_DECELERATION = 0.8;
    private static final int MAX_RESERVE_SEGMENTS = 2;

    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final TrackService trackService;
    private final RouteInterlockingService interlockingService;
    private List<MovementAuthority> authorities = List.of();
    private List<SignalState> signalStates = List.of();

    /** 每列车在站台已停 tick 数（用于停站时间控制） */
    private final Map<String, Integer> stationDwellTicks = new LinkedHashMap<>();
    /** 每列车同一站停站完成后的释放锁存，防止刚释放又被同一站二次截停。 */
    private final Set<String> releasedStationStops = new HashSet<>();
    /** 每列车当前是否在站台停车中 */
    private final Map<String, Boolean> atStationStop = new LinkedHashMap<>();
    /** 默认站台停站秒数（仅在没有调度停站时间时使用） */
    private static final int DEFAULT_DWELL_SECONDS = 25;

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

        for (int i = 0; i < ordered.size(); i++) {
            TrainState train = ordered.get(i);
            double trainHead = train.positionMeters();

            // ---- 故障安全：刹车/牵引失效 → 加大安全距离 ----
            double effectiveSafetyGap = resolveSafetyGap(train);

            double nextTrainTailLimit = Double.POSITIVE_INFINITY;
            if (i + 1 < ordered.size()) {
                TrainState nextTrain = ordered.get(i + 1);
                nextTrainTailLimit = nextTrain.positionMeters() - nextTrain.lengthMeters() - effectiveSafetyGap;
            }

            double lineEndLimit = lineLengthMeters;
            double faultLimit = trackService.nextFaultPosition(trainHead) - effectiveSafetyGap;
            boolean waitingForRoute = interlockingService.isRouteHoldActive(train.id());
            double interlockingLimit = waitingForRoute
                ? trainHead
                : interlockingService.maLimitFromRouteConflict(train.id());

            // ---- 停站控制：MA终点收到站台位置前，等停站时间到才延伸 ----
            Map<String, Double> stationLimits = resolveStationDwell(train);
            if (stationLimits.containsKey("maEndAt")) {
                lineEndLimit = Math.min(lineEndLimit, stationLimits.get("maEndAt"));
            }

            double authorityEnd = Math.min(
                Math.min(nextTrainTailLimit, lineEndLimit),
                Math.min(faultLimit, interlockingLimit)
            );
            authorityEnd = Math.max(trainHead, Math.min(authorityEnd, lineLengthMeters));

            TrackSegmentState currentSeg = trackService.segmentAt(trainHead);
            allReserved.addAll(collectTopologyReserved(train.id(), currentSeg.id(), authorityEnd));

            TrackConstraint track = trackByTrain.get(train.id());
            double segmentSpeedLimit = track == null
                ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
                : track.speedLimitMetersPerSecond();

            double maDistance = Math.max(0, authorityEnd - trainHead);
            double safeBrakingSpeed = Math.sqrt(2 * DEFAULT_BRAKING_DECELERATION * maDistance);

            DispatchConstraint dispatch = dispatchByTrain.get(train.id());
            double dispatchLimitedSpeed = dispatch == null
                ? safeBrakingSpeed
                : dispatch.applyToSpeedLimit(safeBrakingSpeed);

            // 如已在站台停靠中且 MA 已到站台位置，速度应降到 0
            if (stationLimits.containsKey("isDwelling") && stationLimits.get("isDwelling") > 0) {
                dispatchLimitedSpeed = 0;
            }

            double speedLimit = Math.min(segmentSpeedLimit, dispatchLimitedSpeed);
            String reason = buildReason(authorityEnd, nextTrainTailLimit, lineEndLimit,
                faultLimit, interlockingLimit, lineLengthMeters);
            if (stationLimits.containsKey("isDwelling") && stationLimits.get("isDwelling") > 0) {
                int dwellElapsedSec = stationLimits.getOrDefault("dwellElapsedSec", 0.0).intValue();
                int dwellTargetSec = stationLimits.getOrDefault("dwellTargetSec", (double) DEFAULT_DWELL_SECONDS).intValue();
                reason = "站台停靠(" + dwellElapsedSec + "/" + dwellTargetSec + "s)";
            }
            if (waitingForRoute) {
                reason = "等待进路建立(" + interlockingService.routeHoldReason(train.id()) + ")";
            }

            String segId = currentSeg != null ? currentSeg.id() : "?";
            nextAuthorities.add(new MovementAuthority(train.id(), authorityEnd, speedLimit, reason, segId));
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
                "SIG-" + seg.id(),
                seg.id(),
                seg.startMeters(),
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
            if (seg == null || seg.startMeters() >= maEndMeters) {
                break;
            }
            if (seg.occupancy() == TrackOccupancy.OCCUPIED || seg.occupancy() == TrackOccupancy.FAULT) {
                break;
            }
            ids.add(seg.id());
            steps++;
        }
        return ids;
    }

    private Set<String> collectReservedAlongActiveTopology(String startSegmentId, double maEndMeters) {
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> forwardMap = trackService.forwardNeighborMap();
        String current = startSegmentId;
        int steps = 0;
        while (current != null && steps < MAX_RESERVE_SEGMENTS) {
            List<String> forward = forwardMap.getOrDefault(current, List.of());
            if (forward.isEmpty()) {
                break;
            }

            String next = forward.size() == 1 ? forward.get(0) : chooseActiveForwardNeighbor(current, forward);
            TrackSegmentState seg = findSegment(next);
            if (seg == null || seg.startMeters() >= maEndMeters) {
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
        // 1. Prefer the switch-activated branch: if any switch's activeSegmentId
        //    is in the forward set, that's the currently set path.
        for (SwitchState sw : trackService.switchStates()) {
            if (forward.contains(sw.activeSegmentId())) {
                return sw.activeSegmentId();
            }
        }

        // 2. Fallback: pick the forward neighbor with higher speed limit (main track)
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
    private Map<String, Double> resolveStationDwell(TrainState train) {
        Map<String, Double> result = new LinkedHashMap<>();
        OperationalLineData lineData = infrastructureCatalog.lineData();
        List<OperationalLineData.StationDefinition> stations = lineData.stations();
        if (stations.isEmpty()) return result;

        double head = train.positionMeters();

        // 找车头前方的下一站；允许车头在站中心后方 10m 内继续被视为站停窗口。
        OperationalLineData.StationDefinition nextStation = stations.stream()
            .filter(s -> s.centerMeters() >= head - 10)
            .min(Comparator.comparingDouble(s -> s.centerMeters() - head))
            .orElse(null);
        if (nextStation == null) return result;

        String dwellKey = train.id() + ":" + nextStation.id();
        boolean stopped = Math.abs(head - nextStation.centerMeters()) < 10 && train.zeroSpeed();
        if (releasedStationStops.contains(dwellKey)) {
            atStationStop.remove(train.id());
            return result;
        }
        int tickCount = stationDwellTicks.getOrDefault(dwellKey, 0);

        if (stopped) {
            stationDwellTicks.put(dwellKey, tickCount + 1);
            atStationStop.put(train.id(), true);
        }

        int dwellTicks = stationDwellTicks.getOrDefault(dwellKey, 0);
        int targetDwellTicks = targetDwellTicks();
        int dwellElapsedSec = (int) Math.floor(dwellTicks * simulationProperties.getTickMillis() / 1000.0);

        // 站停未完成 → MA 截到站台位置
        if (dwellTicks < targetDwellTicks) {
            result.put("maEndAt", nextStation.centerMeters() + 10);
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
