package com.railwaysim.api.dto;

import java.time.Instant;

public record VehicleMaintenanceStateResponse(
    String trainId,
    String serviceNo,
    String maintenanceState,
    String faultCode,
    int faultLevel,
    String selfCheckStatus,
    String availableOperationMode,
    String dataQuality,
    Instant updatedAt
) {
}
