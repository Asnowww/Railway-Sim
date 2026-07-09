package com.railwaysim.vehicle.runtime;

import java.time.Instant;

public record VehicleRuntimeInstanceState(
    String trainId,
    String lifecycleState,
    String controlQueueStatus,
    String simulationQueueStatus,
    long lastTick,
    long latencyMillis,
    String dataQuality,
    String reason,
    Instant updatedAt
) {
}
