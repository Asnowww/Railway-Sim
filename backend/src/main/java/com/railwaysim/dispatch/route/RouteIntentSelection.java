package com.railwaysim.dispatch.route;

import java.util.List;
import java.util.Map;

public record RouteIntentSelection(
    TrainRouteIntent selectedIntent,
    List<TrainRouteIntent> waitingIntents,
    Map<String, Double> priorityScores
) {
    public RouteIntentSelection {
        waitingIntents = waitingIntents == null ? List.of() : List.copyOf(waitingIntents);
        priorityScores = priorityScores == null ? Map.of() : Map.copyOf(priorityScores);
    }
}
