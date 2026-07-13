package com.railwaysim.vehicle.telemetry;

public record VehicleTelemetryResult(
    String trainId,
    String status,
    Long effectiveFromTick,
    String reason
) {
}
