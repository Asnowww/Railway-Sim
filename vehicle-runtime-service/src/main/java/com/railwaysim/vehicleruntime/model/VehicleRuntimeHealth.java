package com.railwaysim.vehicleruntime.model;

import java.time.Instant;

public record VehicleRuntimeHealth(
    String mode,
    String heartbeatStatus,
    Instant sourceTimestamp,
    long latencyMillis,
    String dataQuality,
    int instanceCount,
    String reason,
    String physicsMode,
    String fmuModelVersion,
    String parameterSetId,
    long fmuBatchLatencyMillis,
    int fallbackTrainCount,
    long totalFleetTickCount,
    long missedDeadlineCount,
    long fallbackEventCount,
    long fmiErrorCount,
    String simulationRunId,
    long lastAcceptedTick,
    String topologyHash,
    String configHash,
    String stoppingParameterVersion
) {
}
