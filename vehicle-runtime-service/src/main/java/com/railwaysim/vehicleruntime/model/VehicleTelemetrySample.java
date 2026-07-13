package com.railwaysim.vehicleruntime.model;

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
