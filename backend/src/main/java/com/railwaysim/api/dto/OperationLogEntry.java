package com.railwaysim.api.dto;

import java.time.Instant;

public record OperationLogEntry(
    String operator,
    String operationType,
    String targetRef,
    String beforeState,
    String afterState,
    String reason,
    String traceId,
    Instant createdAt,
    String status,
    int retryCount
) {
    public OperationLogEntry(
        String operator, String operationType, String targetRef,
        String beforeState, String afterState, String reason, String traceId, Instant createdAt
    ) {
        this(operator, operationType, targetRef, beforeState, afterState, reason, traceId, createdAt, "PENDING", 0);
    }

    public OperationLogEntry withStatus(String status) {
        return new OperationLogEntry(operator, operationType, targetRef, beforeState, afterState,
            reason, traceId, createdAt, status, retryCount);
    }

    public OperationLogEntry withRetry(int retryCount) {
        return new OperationLogEntry(operator, operationType, targetRef, beforeState, afterState,
            reason, traceId, createdAt, status, retryCount);
    }
}
