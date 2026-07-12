package com.railwaysim.vehicle.onboard;

import java.time.Instant;

/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public record OnboardTrainNodeState(
    String trainId,
    String subsystemId,
    String requestedMode,
    String activeMode,
    String connectionState,
    String dataQuality,
    Instant registeredAt,
    Instant lastHeartbeatAt,
    Instant lastControlAt,
    Instant leaseExpiresAt,
    String lastError
) {
}
