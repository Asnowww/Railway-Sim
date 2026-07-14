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

    public double speedLimitAt(double positionMeters, String segmentId, double fallbackMetersPerSecond) {
        return speedLimitZones.stream()
            .filter(zone -> zoneAppliesToSegment(zone, segmentId))
            .filter(zone -> positionMeters >= zone.startMeters() && positionMeters < zone.endMeters())
            .mapToDouble(SpeedLimitZone::speedLimitMetersPerSecond)
            .min()
            .orElse(fallbackMetersPerSecond);
    }

    private static boolean zoneAppliesToSegment(SpeedLimitZone zone, String segmentId) {
        if (segmentId == null || segmentId.isBlank()) {
            return true;
        }
        return zone.segmentId() == null || zone.segmentId().isBlank() || zone.segmentId().equals(segmentId);
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

    public double stationControlDistanceMeters(double positionMeters, double stopWindowMeters) {
        double window = Math.max(0, stopWindowMeters);
        return stations.stream()
            .mapToDouble(station -> station.centerMeters() - positionMeters)
            .filter(distance -> Math.abs(distance) <= window)
            .map(Math::abs)
            .min()
            .orElseGet(() -> nextStationDistanceMeters(positionMeters));
    }

    /**
     * 车头精确停车点：列车所在股道站台的 {@code stopRightMeters}（行车方向末端）。
     * 车长 = 站台停车窗长度，车头停 stop_right 即整车对齐站台。
     * 无站台/无停车窗数据时回退站中心。
     */
    public double stopPointMeters(StationDefinition station, String track) {
        if (station == null) {
            return Double.NaN;
        }
        List<PlatformDefinition> stationPlatforms = platforms.stream()
            .filter(platform -> station.platformIds().contains(platform.id()))
            .toList();
        Optional<PlatformDefinition> matched = stationPlatforms.stream()
            .filter(platform -> track != null && track.equalsIgnoreCase(platform.directionCode()))
            .findFirst()
            .or(() -> stationPlatforms.stream().findFirst());
        return matched
            .filter(platform -> platform.stopRightMeters() > platform.stopLeftMeters())
            .map(PlatformDefinition::stopRightMeters)
            .orElse(station.centerMeters());
    }

    /**
     * 股道感知的停站控制距离：以停车点（{@link #stopPointMeters}）为参考。
     * 停站窗口内返回<b>有符号</b>距离（越过停车点为负，供车辆层判定已对位），
     * 窗口外返回前方最近停车点的距离。
     */
    public double stationControlDistanceMeters(double positionMeters, double stopWindowMeters, String track) {
        double window = Math.max(0, stopWindowMeters);
        double inWindow = Double.NaN;
        double nextAhead = Double.POSITIVE_INFINITY;
        for (StationDefinition station : stations) {
            double stopPoint = stopPointMeters(station, track);
            if (Double.isNaN(stopPoint)) {
                continue;
            }
            double distance = stopPoint - positionMeters;
            if (Math.abs(distance) <= window
                && (Double.isNaN(inWindow) || Math.abs(distance) < Math.abs(inWindow))) {
                inWindow = distance;
            }
            if (distance >= 0 && distance < nextAhead) {
                nextAhead = distance;
            }
        }
        return Double.isNaN(inWindow) ? nextAhead : inWindow;
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
        List<String> sideNeighborSegmentIds,
        String fromNodeId,
        String toNodeId,
        String track
    ) {
        public TrackSegmentDefinition {
            forwardNeighborSegmentIds = List.copyOf(forwardNeighborSegmentIds);
            sideNeighborSegmentIds = List.copyOf(sideNeighborSegmentIds);
            fromNodeId = fromNodeId == null ? "" : fromNodeId;
            toNodeId = toNodeId == null ? "" : toNodeId;
            track = track == null ? "main" : track;
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
        String interoperabilityId,
        String defaultPosition
    ) {
        public SwitchDefinition {
            defaultPosition = (defaultPosition == null || defaultPosition.isBlank()) ? "NORMAL" : defaultPosition;
        }
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
        String interoperabilityId,
        double stopLeftMeters,
        double stopRightMeters,
        String platformSide
    ) {
        /** 兼容旧调用（xls 工作簿路径）：无停车窗口/站台侧信息。 */
        public PlatformDefinition(
            String id,
            double centerMeters,
            String anchorSegmentId,
            String directionCode,
            String rawCenterMark,
            String interoperabilityId
        ) {
            this(id, centerMeters, anchorSegmentId, directionCode, rawCenterMark, interoperabilityId, 0, 0, null);
        }
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
