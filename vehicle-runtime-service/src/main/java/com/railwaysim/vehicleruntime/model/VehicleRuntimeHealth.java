package com.railwaysim.vehicleruntime.model;

import java.time.Instant;

public record VehicleRuntimeHealth(
    String mode,
    String heartbeatStatus,
    Instant sourceTimestamp,
    long latencyMillis,
    String dataQuality,
    int instanceCount,
    String reason
) {
}
