package com.railwaysim.vehicle.external;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalLineData.TrackSegmentDefinition;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExternalSegmentMapper {

    private final List<TrackSegmentDefinition> segments;
    private final Map<String, Integer> configuredSegmentMapping;

    public ExternalSegmentMapper(
        OperationalLineData lineData,
        Map<String, Integer> configuredSegmentMapping
    ) {
        this.segments = lineData == null
            ? List.of()
            : lineData.trackSegments().stream()
                .sorted(Comparator.comparingDouble(TrackSegmentDefinition::startMeters))
                .toList();
        this.configuredSegmentMapping = configuredSegmentMapping == null
            ? Map.of()
            : new LinkedHashMap<>(configuredSegmentMapping);
    }

    public ExternalSegmentPosition mapPosition(double positionMeters) {
        if (segments.isEmpty()) {
            return new ExternalSegmentPosition(1, Math.max(0, positionMeters));
        }

        TrackSegmentDefinition segment = segmentAt(positionMeters);
        int segmentIndex = segments.indexOf(segment);
        int externalSegNo = configuredSegmentMapping.getOrDefault(
            segment.id(),
            segment.rawSegmentId() > 0 ? segment.rawSegmentId() : segmentIndex + 1
        );
        double segmentLength = segmentLength(segment);
        double offset = clamp(positionMeters - segment.startMeters(), 0, segmentLength);
        return new ExternalSegmentPosition(externalSegNo, offset);
    }

    private TrackSegmentDefinition segmentAt(double positionMeters) {
        if (positionMeters <= segments.get(0).startMeters()) {
            return segments.get(0);
        }
        return segments.stream()
            .filter(segment -> positionMeters >= segment.startMeters() && positionMeters < segment.endMeters())
            .findFirst()
            .orElse(segments.get(segments.size() - 1));
    }

    private double segmentLength(TrackSegmentDefinition segment) {
        if (segment.lengthMeters() > 0) {
            return segment.lengthMeters();
        }
        return Math.max(0, segment.endMeters() - segment.startMeters());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
