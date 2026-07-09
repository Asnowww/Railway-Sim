package com.railwaysim.vehicleruntime.model;

public record VehicleRuntimeBootstrapRequest(
    double defaultLineLengthMeters,
    double defaultSpeedLimitMetersPerSecond,
    double safetyGapMeters
) {
}
