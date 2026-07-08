package com.railwaysim.api.dto;

import java.time.Instant;

public record PowerMaintenanceLockResponse(
    String sectionId,
    String sectionName,
    String maintenanceState,
    String lockoutState,
    String breakerStatus,
    String status,
    Instant updatedAt
) {
}
