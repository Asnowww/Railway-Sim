package com.railwaysim.vehicle;

public record VehiclePhysicsOutput(
    String trainId,
    double newPositionMeters,
    double newSpeedMetersPerSecond,
    double accelerationMetersPerSecondSquared,
    double tractionForceNewtons,
    double brakeForceNewtons,
    double regenBrakeForceNewtons,
    double tractionPowerWatts,
    double railCurrentAmps,
    double regenPowerWatts,
    double energyConsumedKwh,
    double energyRegeneratedKwh,
    String faultCode
) {
}
