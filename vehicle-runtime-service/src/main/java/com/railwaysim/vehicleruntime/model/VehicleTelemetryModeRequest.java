package com.railwaysim.vehicleruntime.model;

public record VehicleTelemetryModeRequest(
    VehicleTelemetryMode mode,
    boolean recoverFromHold,
    boolean clearEmergencyBrakeLatch
) {
}
