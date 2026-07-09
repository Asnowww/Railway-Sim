package com.railwaysim.dispatch.disturbance;

import java.time.Instant;

public record DisturbanceEvent(
    String id,
    String simulationRunId,
    String trainId,
    String stationId,
    DisturbanceType disturbanceType,
    double deviationValue,
    String status,
    Instant recordedAt,
    Instant resolvedAt,
    String commandId
) {
}
