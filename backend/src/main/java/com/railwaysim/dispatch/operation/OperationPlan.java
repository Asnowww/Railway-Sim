package com.railwaysim.dispatch.operation;

import java.time.Instant;
import java.util.List;

public record OperationPlan(
    String planId,
    String simulationRunId,
    String routeId,
    String routeName,
    String direction,
    String trainId,
    String originPointId,
    String destinationPointId,
    List<String> viaPointIds,
    List<String> pointIds,
    List<String> stationIds,
    List<String> segmentIds,
    Instant plannedDepartureAt,
    String status,
    int priority,
    int version,
    Instant createdAt,
    Instant updatedAt,
    String routeCommandId,
    String rejectReason,
    String circulationPlanId,
    String circulationLegId,
    Integer cycleIndex,
    Integer legIndex
) {
    public OperationPlan {
        viaPointIds = viaPointIds == null ? List.of() : List.copyOf(viaPointIds);
        pointIds = pointIds == null ? List.of() : List.copyOf(pointIds);
        stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
        segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
    }

    public OperationPlan withRouteRequested(String commandId, Instant updatedAt) {
        return new OperationPlan(
            planId, simulationRunId, routeId, routeName, direction, trainId,
            originPointId, destinationPointId, viaPointIds, pointIds, stationIds, segmentIds,
            plannedDepartureAt, OperationPlanStatus.ROUTE_REQUESTED, priority, version + 1,
            createdAt, updatedAt, commandId, rejectReason,
            circulationPlanId, circulationLegId, cycleIndex, legIndex
        );
    }

    public OperationPlan withStatus(String status, String rejectReason, Instant updatedAt) {
        return new OperationPlan(
            planId, simulationRunId, routeId, routeName, direction, trainId,
            originPointId, destinationPointId, viaPointIds, pointIds, stationIds, segmentIds,
            plannedDepartureAt, status, priority, version + 1,
            createdAt, updatedAt, routeCommandId, rejectReason,
            circulationPlanId, circulationLegId, cycleIndex, legIndex
        );
    }
}
