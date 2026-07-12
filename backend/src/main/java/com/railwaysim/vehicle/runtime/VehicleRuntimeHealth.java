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
    String stoppingParameterVersion
) {
    public VehicleRuntimeHealth(
        VehicleRuntimeMode mode, String heartbeatStatus, Instant sourceTimestamp,
        long latencyMillis, String dataQuality, int instanceCount, String reason
    ) {
        this(mode, heartbeatStatus, sourceTimestamp, latencyMillis, dataQuality, instanceCount,
            reason, "", "", "", "", -1, "", "", "");
    }

    public static VehicleRuntimeHealth local() {
        return new VehicleRuntimeHealth(VehicleRuntimeMode.LOCAL, "LOCAL", Instant.now(), 0, "GOOD", 0, "LOCAL_RUNTIME");
    }

    public static VehicleRuntimeHealth fallback(VehicleRuntimeMode mode, String reason) {
        return new VehicleRuntimeHealth(mode == null ? VehicleRuntimeMode.LOCAL : mode, "FALLBACK", Instant.now(), 0, "FALLBACK", 0, reason);
    }
}
