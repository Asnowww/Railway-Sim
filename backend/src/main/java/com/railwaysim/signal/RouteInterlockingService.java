package com.railwaysim.signal;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RouteInterlockingService {

    private static final Logger log = LoggerFactory.getLogger(RouteInterlockingService.class);

    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final TrackService trackService;
    private final Map<String, RouteState> routeStates = new LinkedHashMap<>();

    public RouteInterlockingService(
        StaticInfrastructureCatalog infrastructureCatalog,
        TrackService trackService
    ) {
        this.infrastructureCatalog = infrastructureCatalog;
        this.trackService = trackService;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        routeStates.clear();
        OperationalLineData lineData = infrastructureCatalog.lineData();
        if (lineData.routes() == null) {
            return;
        }
        for (OperationalLineData.RouteDefinition routeDef : lineData.routes()) {
            routeStates.put(routeDef.id(), new RouteState(
                routeDef.id(),
                RouteStatus.AVAILABLE,
                Set.of(),
                null,
                new LinkedHashSet<>(routeDef.axleSectionIds() == null ? List.of() : routeDef.axleSectionIds())
            ));
        }
    }

    public synchronized String establishRoute(String routeId, String trainId) {
        RouteState route = routeStates.get(routeId);
        if (route == null) {
            return "Route " + routeId + " does not exist";
        }
        if (route.status() == RouteStatus.ESTABLISHED) {
            return "Route " + routeId + " is occupied by " + route.establishedByTrainId();
        }

        Set<String> routeSegments = resolvedSegmentIds(route);
        if (routeSegments.isEmpty()) {
            return "Route " + routeId + " has no resolved track segments";
        }

        for (RouteState existing : routeStates.values()) {
            if (existing.status() == RouteStatus.ESTABLISHED
                && intersects(routeSegments, resolvedSegmentIds(existing))) {
                routeStates.put(routeId, route.withConflicted());
                return "Route " + routeId + " conflicts with established route " + existing.routeId();
            }
        }

        Map<OperationalLineData.SwitchDefinition, SwitchPosition> switchRequirements =
            switchRequirements(routeSegments);
        for (Map.Entry<OperationalLineData.SwitchDefinition, SwitchPosition> entry : switchRequirements.entrySet()) {
            OperationalLineData.SwitchDefinition swDef = entry.getKey();
            SwitchPosition required = entry.getValue();
            SwitchState sw = switchState(swDef.id()).orElse(null);
            if (sw == null) {
                continue;
            }
            if (sw.locked()) {
                return "Switch " + swDef.id() + " is locked";
            }
            if (sw.position() != required && !trackService.throwSwitch(swDef.id(), required)) {
                return "Switch " + swDef.id() + " cannot move to " + required;
            }
        }

        Set<String> lockedIds = new LinkedHashSet<>();
        for (OperationalLineData.SwitchDefinition swDef : switchRequirements.keySet()) {
            trackService.lockSwitch(swDef.id());
            lockedIds.add(swDef.id());
        }

        routeStates.put(routeId, new RouteState(
            routeId,
            RouteStatus.ESTABLISHED,
            lockedIds,
            trainId,
            route.axleSegmentIds()
        ));
        log.info("[Interlocking] route {} established by train {}; locked switches={}", routeId, trainId, lockedIds);
        return null;
    }

    public synchronized void releaseRoute(String routeId) {
        RouteState route = routeStates.get(routeId);
        if (route == null || route.status() != RouteStatus.ESTABLISHED) {
            return;
        }

        for (String switchId : route.lockedSwitchIds()) {
            trackService.unlockSwitch(switchId);
        }
        routeStates.put(routeId, route.withReleased());
        restoreResolvedConflicts();
        log.info("[Interlocking] route {} released; unlocked switches={}", routeId, route.lockedSwitchIds());
    }

    public synchronized void releaseAllForTrain(String trainId) {
        List<String> routeIds = routeStates.values().stream()
            .filter(state -> trainId.equals(state.establishedByTrainId()))
            .map(RouteState::routeId)
            .toList();
        routeIds.forEach(this::releaseRoute);
    }

    public synchronized List<RouteState> states() {
        return List.copyOf(routeStates.values());
    }

    public synchronized RouteState state(String routeId) {
        return routeStates.getOrDefault(routeId,
            new RouteState(routeId, RouteStatus.AVAILABLE, Set.of(), null, Set.of()));
    }

    public synchronized double maLimitFromRouteConflict(String trainId) {
        for (RouteState route : routeStates.values()) {
            if (route.status() != RouteStatus.ESTABLISHED) {
                continue;
            }
            if (trainId.equals(route.establishedByTrainId())) {
                continue;
            }
            for (String segId : resolvedSegmentIds(route)) {
                TrackSegmentState seg = findSegmentById(segId).orElse(null);
                if (seg != null
                    && (seg.occupancy() == TrackOccupancy.RESERVED
                    || seg.occupancy() == TrackOccupancy.OCCUPIED)) {
                    return seg.startMeters();
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    public synchronized List<String> establishedSegmentPathForTrain(String trainId) {
        return routeStates.values().stream()
            .filter(route -> route.status() == RouteStatus.ESTABLISHED)
            .filter(route -> trainId.equals(route.establishedByTrainId()))
            .findFirst()
            .map(route -> List.copyOf(resolvedSegmentIds(route)))
            .orElse(List.of());
    }

    public synchronized void touchRoutes(List<TrainState> trains) {
        ensureInitialized();
        for (TrainState train : trains) {
            for (RouteState route : List.copyOf(routeStates.values())) {
                if (route.status() != RouteStatus.AVAILABLE) {
                    continue;
                }
                if (headInRoute(train, route)) {
                    String rejection = establishRoute(route.routeId(), train.id());
                    if (rejection == null) {
                        log.info("[Interlocking] auto-established route {} for train {}", route.routeId(), train.id());
                    }
                }
            }
        }

        for (RouteState route : List.copyOf(routeStates.values())) {
            if (route.status() != RouteStatus.ESTABLISHED) {
                continue;
            }
            if (!anyTrainInRoute(trains, route)) {
                releaseRoute(route.routeId());
            }
        }
    }

    public record RouteDispatchResult(boolean accepted, String rejectReason) {}

    public synchronized RouteDispatchResult applyDispatchCommand(String commandType, String detail, String trainId) {
        return switch (commandType) {
            case "REROUTE" -> {
                String routeId = findBestRoute(detail);
                if (routeId == null) {
                    yield new RouteDispatchResult(false, "No matching route");
                }
                String rejection = establishRoute(routeId, trainId);
                yield rejection == null
                    ? new RouteDispatchResult(true, null)
                    : new RouteDispatchResult(false, rejection);
            }
            default -> new RouteDispatchResult(true, null);
        };
    }

    private void ensureInitialized() {
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        if (routeStates.isEmpty() && routes != null && !routes.isEmpty()) {
            reset();
        }
    }

    private boolean headInRoute(TrainState train, RouteState route) {
        return resolvedSegmentIds(route).stream()
            .map(this::findSegmentById)
            .flatMap(Optional::stream)
            .anyMatch(seg -> train.positionMeters() >= seg.startMeters()
                && train.positionMeters() < seg.endMeters());
    }

    private boolean anyTrainInRoute(List<TrainState> trains, RouteState route) {
        Set<String> segmentIds = resolvedSegmentIds(route);
        return trains.stream().anyMatch(train -> segmentIds.stream()
            .map(this::findSegmentById)
            .flatMap(Optional::stream)
            .anyMatch(seg -> {
                double tail = train.positionMeters() - train.lengthMeters();
                return train.positionMeters() > seg.startMeters() && tail < seg.endMeters();
            }));
    }

    private void restoreResolvedConflicts() {
        List<RouteState> established = routeStates.values().stream()
            .filter(route -> route.status() == RouteStatus.ESTABLISHED)
            .toList();
        for (RouteState route : List.copyOf(routeStates.values())) {
            if (route.status() != RouteStatus.CONFLICTED) {
                continue;
            }
            Set<String> routeSegments = resolvedSegmentIds(route);
            boolean stillConflicts = established.stream()
                .anyMatch(existing -> intersects(routeSegments, resolvedSegmentIds(existing)));
            if (!stillConflicts) {
                routeStates.put(route.routeId(), route.withReleased());
            }
        }
    }

    private Map<OperationalLineData.SwitchDefinition, SwitchPosition> switchRequirements(Set<String> routeSegments) {
        Map<OperationalLineData.SwitchDefinition, SwitchPosition> requirements = new LinkedHashMap<>();
        List<OperationalLineData.SwitchDefinition> switches = infrastructureCatalog.lineData().switches();
        if (switches == null) {
            return requirements;
        }
        for (OperationalLineData.SwitchDefinition sw : switches) {
            boolean usesNormal = routeSegments.contains(sw.normalSegmentId());
            boolean usesReverse = routeSegments.contains(sw.reverseSegmentId());
            if (usesNormal && !usesReverse) {
                requirements.put(sw, SwitchPosition.NORMAL);
            } else if (usesReverse && !usesNormal) {
                requirements.put(sw, SwitchPosition.REVERSE);
            }
        }
        return requirements;
    }

    private Set<String> resolvedSegmentIds(RouteState route) {
        Set<String> ids = new LinkedHashSet<>();
        for (String rawId : route.axleSegmentIds()) {
            resolveSegmentId(rawId).ifPresent(ids::add);
        }
        return ids;
    }

    private Optional<String> resolveSegmentId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        if (findSegmentById(rawId).isPresent()) {
            return Optional.of(rawId);
        }
        String suffix = numericSuffix(rawId);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        return trackService.states().stream()
            .map(TrackSegmentState::id)
            .filter(id -> suffix.equals(numericSuffix(id)))
            .findFirst();
    }

    private Optional<TrackSegmentState> findSegmentById(String id) {
        return trackService.states().stream()
            .filter(segment -> segment.id().equals(id))
            .findFirst();
    }

    private Optional<SwitchState> switchState(String id) {
        return trackService.switchStates().stream()
            .filter(sw -> sw.id().equals(id))
            .findFirst();
    }

    private String findBestRoute(String detail) {
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        return routes.stream()
            .filter(route -> detail != null && route.name() != null && detail.contains(route.name()))
            .map(OperationalLineData.RouteDefinition::id)
            .findFirst()
            .orElse(null);
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String id : left) {
            if (right.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static String numericSuffix(String value) {
        return value.replaceAll("[^0-9]", "");
    }
}
