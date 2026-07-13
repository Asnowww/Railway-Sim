package com.railwaysim.vehicle.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VehicleRuntimeHealth(
    VehicleRuntimeMode mode,
    String heartbeatStatus,
    Instant sourceTimestamp,
    long latencyMillis,
    String dataQuality,
    int instanceCount,
    String reason,
    String physicsMode,
    String fmuModelVersion,
    String parameterSetId,
    String simulationRunId,
    long lastAcceptedTick,
    String topologyHash,
    String configHash,
    String stoppingParameterVersion,
    boolean bootstrapped
) {
    public VehicleRuntimeHealth(
        VehicleRuntimeMode mode, String heartbeatStatus, Instant sourceTimestamp,
        long latencyMillis, String dataQuality, int instanceCount, String reason
    ) {
        this(mode, heartbeatStatus, sourceTimestamp, latencyMillis, dataQuality, instanceCount,
            reason, "", "", "", "", -1, "", "", "", false);
    }

    public static VehicleRuntimeHealth fallback(VehicleRuntimeMode mode, String reason) {
        return new VehicleRuntimeHealth(mode == null ? VehicleRuntimeMode.EXTERNAL_HTTP : mode, "FALLBACK", Instant.now(), 0, "FALLBACK", 0, reason);
    }
}
