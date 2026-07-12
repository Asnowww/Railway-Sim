package com.railwaysim.dispatch.route;

import com.railwaysim.track.SwitchPosition;
import java.util.List;
import java.util.Map;

public record DispatchRouteCandidate(
    String routeId,
    String name,
    String typeCode,
    String startSignalId,
    String endSignalId,
    List<String> segmentIds,
    List<String> protectionSectionIds,
    List<String> pointTriggerSectionIds,
    Map<String, SwitchPosition> requiredSwitchPositions,
    double entryMeters,
    double exitMeters,
    double lengthMeters
) {
    public DispatchRouteCandidate {
        segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
        protectionSectionIds = protectionSectionIds == null ? List.of() : List.copyOf(protectionSectionIds);
        pointTriggerSectionIds = pointTriggerSectionIds == null ? List.of() : List.copyOf(pointTriggerSectionIds);
        requiredSwitchPositions = requiredSwitchPositions == null ? Map.of() : Map.copyOf(requiredSwitchPositions);
    }

    public boolean mainline() {
        return "MAIN".equalsIgnoreCase(typeCode);
    }
}
