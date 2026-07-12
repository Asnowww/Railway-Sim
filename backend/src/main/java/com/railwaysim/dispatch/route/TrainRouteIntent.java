package com.railwaysim.dispatch.route;

import java.time.Instant;

public record TrainRouteIntent(
    String trainId,
    String routeId,
    String reason,
    double distanceToEntryMeters,
    double etaToEntrySeconds,
    double priorityScore,
    double waitingSeconds,
    Instant createdAt,
    Instant validUntil
) {
}
