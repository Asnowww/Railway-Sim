package com.railwaysim.vehicle.runtime;

import java.time.Instant;

public record VehicleRuntimeEvent(
    String id,
    String trainId,
    String eventType,
    String detail,
    Instant occurredAt
) {
}
