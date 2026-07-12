package com.railwaysim.dispatch.plan;

import java.util.List;

public record TrainServicePlan(
    String serviceId,
    String circulationId,
    String trainId,
    int trainNo,
    int linkId,
    double offsetMeters,
    String direction,
    List<PlannedStop> stops
) {
    public TrainServicePlan {
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    public PlannedStop origin() {
        return stops.isEmpty() ? null : stops.getFirst();
    }

    public PlannedStop terminus() {
        return stops.isEmpty() ? null : stops.getLast();
    }
}
