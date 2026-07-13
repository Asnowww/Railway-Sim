package com.railwaysim.dispatch.operation;

import java.util.List;

public record OperationRouteCandidate(
    String key,
    String routeId,
    String routeName,
    String direction,
    List<String> pointIds,
    List<String> stationIds,
    List<String> segmentIds
) {
    public OperationRouteCandidate {
        pointIds = pointIds == null ? List.of() : List.copyOf(pointIds);
        stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
        segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
    }
}
