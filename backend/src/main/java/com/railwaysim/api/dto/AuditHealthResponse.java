package com.railwaysim.api.dto;

public record AuditHealthResponse(
    String status,
    long persistedCount,
    long failedCount,
    long pendingCount
) {
}
