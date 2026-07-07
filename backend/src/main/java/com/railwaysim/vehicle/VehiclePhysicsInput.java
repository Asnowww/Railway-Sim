package com.railwaysim.vehicle;

public record VehiclePhysicsInput(
    String trainId,
    double positionMeters,
    double speedMetersPerSecond,
    double trainMassKg,
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrakeCommand,
    double speedLimitMetersPerSecond,
    double movementAuthorityDistanceMeters,
    double gradient,
    double curveRadiusMeters,
    double railVoltage,
    double powerAvailableWatts,
    boolean doorClosed,
    double adhesionCoefficient,
    double previousEnergyConsumedKwh,
    double previousEnergyRegeneratedKwh,
    double deltaSeconds
) {
}
