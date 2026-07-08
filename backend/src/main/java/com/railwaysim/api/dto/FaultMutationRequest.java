package com.railwaysim.api.dto;

public record FaultMutationRequest(
    String faultType,
    String reason,
    String operator,
    String confirmToken,
    String traceId
) {
    public String normalizedOperator() {
        return operator == null || operator.isBlank() ? "simulation" : operator;
    }

    public String normalizedReason() {
        return reason == null || reason.isBlank() ? "demo" : reason;
    }

    public String normalizedTraceId() {
        return traceId == null || traceId.isBlank() ? "trace-simulation" : traceId;
    }
}
