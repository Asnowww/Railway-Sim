package com.railwaysim.power.external;

public record PowerNetworkOperationRequest(
    String operationType,
    String targetType,
    String targetId,
    String desiredState,
    String operator,
    String reason,
    String traceId,
    String confirmToken
) {
    public String normalizedOperator() {
        return operator == null || operator.isBlank() ? "simulation" : operator.trim();
    }

    public String normalizedReason() {
        return reason == null || reason.isBlank() ? "no reason provided" : reason.trim();
    }
}
