package com.railwaysim.monitor;

import java.time.Instant;

public record ServiceHealthObservation(
    String serviceId,
    boolean external,
    String heartbeatStatus,
    String dataQuality,
    Instant sourceTimestamp,
    String simulationRunId,
    long lastAcceptedTick,
    String topologyHash,
    String configHash,
    String modelVersion,
    String parameterVersion,
    String reason
) {
    public ServiceHealthObservation {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId is required");
        }
        heartbeatStatus = normalize(heartbeatStatus, "UNKNOWN");
        dataQuality = normalize(dataQuality, "UNKNOWN");
        simulationRunId = normalize(simulationRunId, "");
        topologyHash = normalize(topologyHash, "");
        configHash = normalize(configHash, "");
        modelVersion = normalize(modelVersion, "");
        parameterVersion = normalize(parameterVersion, "");
        reason = normalize(reason, heartbeatStatus);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
