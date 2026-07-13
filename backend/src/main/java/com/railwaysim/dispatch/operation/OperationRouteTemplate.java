package com.railwaysim.dispatch.operation;

import java.util.List;

public record OperationRouteTemplate(
    String routeId,
    String name,
    String typeCode,
    List<String> pointIds,
    List<String> stationIds,
    List<String> segmentIds
) {
    public OperationRouteTemplate {
        pointIds = pointIds == null ? List.of() : List.copyOf(pointIds);
        stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
        segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
    }
}
