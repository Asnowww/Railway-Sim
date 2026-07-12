package com.railwaysim.dispatch.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RouteIntentArbiter {

    private final RouteCatalog routeCatalog;

    public RouteIntentArbiter(RouteCatalog routeCatalog) {
        this.routeCatalog = routeCatalog;
    }

    public List<RouteIntentSelection> arbitrate(List<TrainRouteIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return List.of();
        }
        List<TrainRouteIntent> remaining = new ArrayList<>(intents.stream()
            .sorted(Comparator
                .comparingDouble(TrainRouteIntent::priorityScore).reversed()
                .thenComparingDouble(TrainRouteIntent::etaToEntrySeconds)
                .thenComparing(TrainRouteIntent::trainId))
            .toList());
        List<RouteIntentSelection> selections = new ArrayList<>();
        while (!remaining.isEmpty()) {
            TrainRouteIntent selected = remaining.removeFirst();
            List<TrainRouteIntent> waiting = remaining.stream()
                .filter(intent -> routeCatalog.conflicts(selected.routeId(), intent.routeId()))
                .toList();
            remaining.removeAll(waiting);

            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put(selected.trainId(), selected.priorityScore());
            for (TrainRouteIntent waitingIntent : waiting) {
                scores.put(waitingIntent.trainId(), waitingIntent.priorityScore());
            }
            selections.add(new RouteIntentSelection(selected, waiting, scores));
        }
        return List.copyOf(selections);
    }

    public boolean conflicts(String leftRouteId, String rightRouteId) {
        return routeCatalog.conflicts(leftRouteId, rightRouteId);
    }
}
