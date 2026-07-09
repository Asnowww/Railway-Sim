package com.railwaysim.power.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PowerNetworkOperationResult(
    boolean accepted,
    boolean executed,
    String targetId,
    String resultState,
    String reason,
    String traceId,
    Instant executedAt
) {
    public PowerNetworkOperationResult {
        resultState = resultState == null ? "" : resultState;
        reason = reason == null ? "" : reason;
        traceId = traceId == null ? "" : traceId;
        executedAt = executedAt == null ? Instant.now() : executedAt;
    }
}
