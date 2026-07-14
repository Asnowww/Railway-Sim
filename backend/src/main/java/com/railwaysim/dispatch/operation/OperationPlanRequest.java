package com.railwaysim.dispatch.operation;

import java.time.Instant;
import java.util.List;

public record OperationPlanRequest(
    List<String> pointIds,
    String routeId,
    String candidateKey,
    String trainId,
    Instant plannedDepartureAt,
    Integer leadSeconds,
    Integer headwaySeconds,
    Integer priority
) {
    public OperationPlanRequest {
        pointIds = pointIds == null ? List.of() : List.copyOf(pointIds);
    }
}
