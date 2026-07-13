package com.railwaysim.vehicleruntime.model;

public record VehicleTelemetryResult(
    String trainId,
    VehicleTelemetryStatus status,
    Long effectiveFromTick,
    String reason
) {
}
