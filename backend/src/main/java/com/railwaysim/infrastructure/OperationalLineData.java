package com.railwaysim.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public record OperationalLineData(
    String lineId,
    String lineName,
    List<PointDefinition> points,
    List<TrackSegmentDefinition> trackSegments,
    List<SpeedLimitZone> speedLimitZones,
    List<GradientZone> gradientZones,
    List<SwitchDefinition> switches,
    List<StationDefinition> stations,
    List<PlatformDefinition> platforms,
    List<SignalDefinition> signals,
    List<BaliseDefinition> balises,
    List<RouteDefinition> routes
) {
    public OperationalLineData {
        points = List.copyOf(points);
        trackSegments = trackSegments.stream()
            .sorted(Comparator.comparingDouble(TrackSegmentDefinition::startMeters))
            .toList();
        speedLimitZones = speedLimitZones.stream()
            .sorted(Comparator.comparingDouble(SpeedLimitZone::startMeters))
            .toList();
        gradientZones = gradientZones.stream()
            .sorted(Comparator.comparingDouble(GradientZone::startMeters))
            .toList();
        switches = List.copyOf(switches);
        stations = stations.stream()
            .sorted(Comparator.comparingDouble(StationDefinition::centerMeters))
            .toList();
        platforms = platforms.stream()
            .sorted(Comparator.comparingDouble(PlatformDefinition::centerMeters))
            .toList();
        signals = List.copyOf(signals);
        balises = List.copyOf(balises);
        routes = List.copyOf(routes);
    }

    public double lineLengthMeters() {
        return trackSegments.stream()
            .mapToDouble(TrackSegmentDefinition::endMeters)
            .max()
            .orElse(0);
    }

    public Optional<TrackSegmentDefinition> segmentAt(double positionMeters) {
        if (trackSegments.isEmpty()) {
            return Optional.empty();
        }
        return trackSegments.stream()
            .filter(segment -> positionMeters >= segment.startMeters() && positionMeters < segment.endMeters())
            .findFirst()
            .or(() -> Optional.of(trackSegments.get(trackSegments.size() - 1)));
    }

    public double speedLimitAt(double positionMeters, double fallbackMetersPerSecond) {
        return speedLimitZones.stream()
            .filter(zone -> positionMeters >= zone.startMeters() && positionMeters < zone.endMeters())
            .mapToDouble(SpeedLimitZone::speedLimitMetersPerSecond)
            .min()
            .orElse(fallbackMetersPerSecond);
    }

    public double gradientAt(double positionMeters) {
        return gradientZones.stream()
            .filter(zone -> positionMeters >= zone.startMeters() && positionMeters < zone.endMeters())
            .min(Comparator.comparingDouble(zone -> zone.endMeters() - zone.startMeters()))
            .map(GradientZone::gradient)
            .orElse(0.0);
    }

    public double nextStationDistanceMeters(double positionMeters) {
        return stations.stream()
            .mapToDouble(station -> station.centerMeters() - positionMeters)
            .filter(distance -> distance >= 0)
            .min()
            .orElse(Double.POSITIVE_INFINITY);
    }

    public Map<String, TrackSegmentDefinition> trackSegmentById() {
        return trackSegments.stream()
            .collect(Collectors.toMap(TrackSegmentDefinition::id, Function.identity(), (left, right) -> left));
    }

    public record PointDefinition(
        String id,
        String name,
        String trackName,
        double kilometerMarkMeters,
        String directionCode
    ) {
    }

    public record TrackSegmentDefinition(
        String id,
        int rawSegmentId,
        double startMeters,
        double endMeters,
        double lengthMeters,
        double defaultSpeedLimitMetersPerSecond,
        int startEndpointType,
        int startEndpointId,
        int endEndpointType,
        int endEndpointId,
        List<String> forwardNeighborSegmentIds,
        List<String> sideNeighborSegmentIds
    ) {
        public TrackSegmentDefinition {
            forwardNeighborSegmentIds = List.copyOf(forwardNeighborSegmentIds);
            sideNeighborSegmentIds = List.copyOf(sideNeighborSegmentIds);
        }
    }

    public record SpeedLimitZone(
        String id,
        String segmentId,
        double startMeters,
        double endMeters,
        double speedLimitMetersPerSecond,
        String switchId
    ) {
    }

    public record GradientZone(
        String id,
        double startMeters,
        double endMeters,
        double gradient,
        int rawPermilleValue,
        String directionCode
    ) {
    }

    public record SwitchDefinition(
        String id,
        String name,
        String linkedSwitchId,
        String directionCode,
        String normalSegmentId,
        String reverseSegmentId,
        String mergeSegmentId,
        double divergingSpeedLimitMetersPerSecond,
        String interoperabilityId
    ) {
    }

    public record StationDefinition(
        String id,
        String name,
        double centerMeters,
        List<String> platformIds
    ) {
        public StationDefinition {
            platformIds = List.copyOf(platformIds);
        }
    }

    public record PlatformDefinition(
        String id,
        double centerMeters,
        String anchorSegmentId,
        String directionCode,
        String rawCenterMark,
        String interoperabilityId
    ) {
    }

    public record SignalDefinition(
        String id,
        String name,
        String typeCode,
        String attributeCode,
        String segmentId,
        double positionMeters,
        String protectionDirectionCode,
        String lampInfoCode,
        String interoperabilityId
    ) {
    }

    public record BaliseDefinition(
        String id,
        String hexId,
        String name,
        String segmentId,
        double positionMeters,
        String interoperabilityId,
        String attributeCode,
        String linkedSignalId,
        String directionCode
    ) {
    }

    public record RouteDefinition(
        String id,
        String name,
        String typeCode,
        String startSignalId,
        String endSignalId,
        List<String> axleSectionIds,
        List<String> protectionSectionIds,
        List<String> pointApproachSectionIds,
        List<String> cbtcApproachSectionIds,
        List<String> pointTriggerSectionIds,
        List<String> cbtcTriggerSectionIds
    ) {
        public RouteDefinition {
            axleSectionIds = List.copyOf(axleSectionIds);
            protectionSectionIds = List.copyOf(protectionSectionIds);
            pointApproachSectionIds = List.copyOf(pointApproachSectionIds);
            cbtcApproachSectionIds = List.copyOf(cbtcApproachSectionIds);
            pointTriggerSectionIds = List.copyOf(pointTriggerSectionIds);
            cbtcTriggerSectionIds = List.copyOf(cbtcTriggerSectionIds);
        }
    }
}
