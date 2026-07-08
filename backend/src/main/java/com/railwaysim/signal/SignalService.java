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

            double nextTrainTailLimit = Double.POSITIVE_INFINITY;
            if (i + 1 < ordered.size()) {
                TrainState nextTrain = ordered.get(i + 1);
                nextTrainTailLimit = nextTrain.positionMeters() - nextTrain.lengthMeters() - safetyGap;
            }

            double lineEndLimit = lineLengthMeters;
            double faultLimit = trackService.nextFaultPosition(trainHead) - safetyGap;
            double interlockingLimit = interlockingService.maLimitFromRouteConflict(train.id());

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

            double speedLimit = Math.min(segmentSpeedLimit, dispatchLimitedSpeed);
            String reason = buildReason(authorityEnd, nextTrainTailLimit, lineEndLimit,
                faultLimit, interlockingLimit, lineLengthMeters);

            nextAuthorities.add(new MovementAuthority(train.id(), authorityEnd, speedLimit, reason));
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
