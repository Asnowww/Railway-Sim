package com.railwaysim.vehicleruntime.model;

public record VehicleTelemetryModeState(
    String trainId,
    VehicleTelemetryMode mode,
    boolean holdLatched,
    boolean emergencyBrakeLatched
) {
}
