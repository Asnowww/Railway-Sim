package com.railwaysim.dispatch.route;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.SwitchPosition;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RouteCatalog {

    private final OperationalLineData lineData;

    @Autowired
    public RouteCatalog(StaticInfrastructureCatalog infrastructureCatalog) {
        this(infrastructureCatalog.lineData());
    }

    public RouteCatalog(OperationalLineData lineData) {
        this.lineData = lineData;
    }

    public List<DispatchRouteCandidate> routes() {
        if (lineData == null || lineData.routes() == null || lineData.routes().isEmpty()) {
            return List.of();
        }
        return lineData.routes().stream()
            .map(this::candidateFrom)
            .flatMap(Optional::stream)
            .sorted(Comparator
                .comparingDouble(DispatchRouteCandidate::entryMeters)
                .thenComparing(candidate -> candidate.mainline() ? 0 : 1)
                .thenComparingDouble(DispatchRouteCandidate::lengthMeters)
                .thenComparing(DispatchRouteCandidate::routeId))
            .toList();
    }

    public Optional<DispatchRouteCandidate> route(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            return Optional.empty();
        }
        return routes().stream()
            .filter(route -> route.routeId().equals(routeId))
            .findFirst();
    }

    public List<DispatchRouteCandidate> candidateRoutesNear(
        double positionMeters,
        String currentSegmentId,
        double lookaheadMeters
    ) {
        double window = Math.max(0, lookaheadMeters);
        return routes().stream()
            .filter(route -> isApproaching(route, positionMeters, currentSegmentId, window))
            .sorted(Comparator
                .comparingDouble((DispatchRouteCandidate route) -> distanceToEntry(route, positionMeters))
                .thenComparing(route -> route.mainline() ? 0 : 1)
                .thenComparingDouble(DispatchRouteCandidate::lengthMeters)
                .thenComparing(DispatchRouteCandidate::routeId))
            .toList();
    }

    private boolean isApproaching(
        DispatchRouteCandidate route,
        double positionMeters,
        String currentSegmentId,
        double lookaheadMeters
    ) {
        if (currentSegmentId != null && route.segmentIds().contains(currentSegmentId)) {
            return positionMeters <= route.exitMeters();
        }
        double distance = distanceToEntry(route, positionMeters);
        return distance >= -routeEntryToleranceMeters(route) && distance <= lookaheadMeters;
    }

    private double distanceToEntry(DispatchRouteCandidate route, double positionMeters) {
        return route.entryMeters() - positionMeters;
    }

    private double routeEntryToleranceMeters(DispatchRouteCandidate route) {
        return Math.min(80, Math.max(20, route.lengthMeters() * 0.05));
    }

    private Optional<DispatchRouteCandidate> candidateFrom(OperationalLineData.RouteDefinition route) {
        List<OperationalLineData.TrackSegmentDefinition> segments = route.axleSectionIds().stream()
            .map(this::resolveSegment)
            .flatMap(Optional::stream)
            .toList();
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        double entry = signalPosition(route.startSignalId())
            .orElseGet(() -> segments.stream()
                .mapToDouble(OperationalLineData.TrackSegmentDefinition::startMeters)
                .min()
                .orElse(0));
        double exit = signalPosition(route.endSignalId())
            .orElseGet(() -> segments.stream()
                .mapToDouble(OperationalLineData.TrackSegmentDefinition::endMeters)
                .max()
                .orElse(entry));
        double min = segments.stream()
            .mapToDouble(OperationalLineData.TrackSegmentDefinition::startMeters)
            .min()
            .orElse(entry);
        double max = segments.stream()
            .mapToDouble(OperationalLineData.TrackSegmentDefinition::endMeters)
            .max()
            .orElse(exit);
        Map<String, SwitchPosition> switchRequirements = switchRequirements(route.axleSectionIds());

        return Optional.of(new DispatchRouteCandidate(
            route.id(),
            route.name(),
            route.typeCode(),
            route.startSignalId(),
            route.endSignalId(),
            route.axleSectionIds(),
            route.protectionSectionIds(),
            route.pointTriggerSectionIds(),
            switchRequirements,
            entry,
            exit,
            Math.max(0, max - min)
        ));
    }

    private Map<String, SwitchPosition> switchRequirements(List<String> routeSegmentIds) {
        if (lineData.switches() == null || lineData.switches().isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(routeSegmentIds == null ? List.of() : routeSegmentIds);
        Map<String, SwitchPosition> requirements = new LinkedHashMap<>();
        for (OperationalLineData.SwitchDefinition sw : lineData.switches()) {
            boolean usesNormal = ids.contains(sw.normalSegmentId());
            boolean usesReverse = ids.contains(sw.reverseSegmentId());
            if (usesNormal && !usesReverse) {
                requirements.put(sw.id(), SwitchPosition.NORMAL);
            } else if (usesReverse && !usesNormal) {
                requirements.put(sw.id(), SwitchPosition.REVERSE);
            }
        }
        return requirements;
    }

    private Optional<OperationalLineData.TrackSegmentDefinition> resolveSegment(String segmentId) {
        if (segmentId == null || segmentId.isBlank() || lineData.trackSegments() == null) {
            return Optional.empty();
        }
        Optional<OperationalLineData.TrackSegmentDefinition> exact = lineData.trackSegments().stream()
            .filter(segment -> segment.id().equals(segmentId))
            .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        String suffix = numericSuffix(segmentId);
        if (suffix.isBlank()) {
            return Optional.empty();
        }
        return lineData.trackSegments().stream()
            .filter(segment -> suffix.equals(numericSuffix(segment.id())))
            .findFirst();
    }

    private Optional<Double> signalPosition(String signalId) {
        if (signalId == null || signalId.isBlank() || lineData.signals() == null) {
            return Optional.empty();
        }
        return lineData.signals().stream()
            .filter(signal -> signal.id().equals(signalId))
            .map(OperationalLineData.SignalDefinition::positionMeters)
            .findFirst();
    }

    private static String numericSuffix(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }
}
