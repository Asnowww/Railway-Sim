package com.railwaysim.dispatch.route;

import java.time.Instant;

public record RouteReservation(
    String reservationId,
    String simulationRunId,
    String trainId,
    String routeId,
    String decisionId,
    String state,
    String commandId,
    String rejectReason,
    String failureCode,
    String failureCategory,
    boolean retryable,
    int retryCount,
    Instant requestedAt,
    Instant acceptedAt,
    Instant releasedAt,
    Instant expiresAt,
    Instant timedOutAt,
    String cancelCommandId,
    Instant updatedAt
) {
}
