package com.railwaysim.vehicle.runtime;

public record VehicleRuntimeBootstrapRequest(
    double defaultLineLengthMeters,
    double defaultSpeedLimitMetersPerSecond,
    double safetyGapMeters
) {
}
