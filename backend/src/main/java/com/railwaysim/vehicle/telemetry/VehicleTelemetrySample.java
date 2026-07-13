package com.railwaysim.vehicle.telemetry;

public record VehicleTelemetrySample(
    String trainId,
    double speedMetersPerSecond,
    double cumulativeDistanceMeters,
    String direction,
    double loadMassKg,
    double faultSpeedLimitMetersPerSecond,
    boolean emergencyBrakeApplied,
    int availableTractionCount,
    int availableBrakeCount
) {
}
