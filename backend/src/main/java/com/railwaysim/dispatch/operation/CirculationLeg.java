package com.railwaysim.dispatch.operation;

import java.time.Instant;
import java.util.List;

public record CirculationLeg(
    String legId,
    String routeId,
    String routeName,
    String direction,
    String legType,
    String fromPointId,
    String toPointId,
    List<String> pointIds,
    List<String> stationIds,
    List<String> segmentIds,
    int cycleIndex,
    int legIndex,
    Instant plannedDepartureAt,
    String status,
    String operationPlanId,
    String routeCommandId,
    String rejectReason
) {
    public CirculationLeg {
        pointIds = pointIds == null ? List.of() : List.copyOf(pointIds);
        stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
        segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
    }

    public CirculationLeg withPlan(String operationPlanId, Instant plannedDepartureAt) {
        return new CirculationLeg(
            legId, routeId, routeName, direction, legType, fromPointId, toPointId,
            pointIds, stationIds, segmentIds, cycleIndex, legIndex, plannedDepartureAt,
            CirculationLegStatus.PLANNED, operationPlanId, routeCommandId, rejectReason
        );
    }

    public CirculationLeg withStatus(String status, String routeCommandId, String rejectReason) {
        return new CirculationLeg(
            legId, routeId, routeName, direction, legType, fromPointId, toPointId,
            pointIds, stationIds, segmentIds, cycleIndex, legIndex, plannedDepartureAt,
            status, operationPlanId, routeCommandId, rejectReason
        );
    }
}
