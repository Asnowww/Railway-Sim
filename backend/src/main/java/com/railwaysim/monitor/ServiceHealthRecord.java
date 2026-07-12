package com.railwaysim.monitor;

import java.time.Instant;

public record ServiceHealthRecord(
    String serviceId,
    ServiceHealthState state,
    String dataQuality,
    Instant sourceTimestamp,
    Instant observedAt,
    String simulationRunId,
    long lastAcceptedTick,
    String topologyHash,
    String configHash,
    String modelVersion,
    String parameterVersion,
    String reason,
    RecoveryGate recoveryGate
) {
}
