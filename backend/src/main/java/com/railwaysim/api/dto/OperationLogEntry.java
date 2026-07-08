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
    Instant createdAt
) {
}
