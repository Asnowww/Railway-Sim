package com.railwaysim.dispatch.route;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RouteDispatchDecision(
    String decisionId,
    String simulationRunId,
    String selectedTrainId,
    String selectedRouteId,
    List<String> waitingTrainIds,
    Map<String, Double> priorityScores,
    String reason,
    String status,
    String routeCommandId,
    List<String> waitingCommandIds,
    String rejectReason,
    Instant createdAt,
    Instant updatedAt
) {
    public RouteDispatchDecision {
        waitingTrainIds = waitingTrainIds == null ? List.of() : List.copyOf(waitingTrainIds);
        priorityScores = priorityScores == null ? Map.of() : Map.copyOf(priorityScores);
        waitingCommandIds = waitingCommandIds == null ? List.of() : List.copyOf(waitingCommandIds);
    }
}
