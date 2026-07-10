package com.railwaysim.vehicleruntime.model;

public record VehiclePhysicsOutputDto(
    String trainId,
    double newPositionMeters,
    double newSpeedMetersPerSecond,
    double accelerationMetersPerSecondSquared,
    double tractionForceNewtons,
    double brakeForceNewtons,
    double regenBrakeForceNewtons,
    double mechanicalTractionPowerWatts,
    double tractionPowerWatts,
    double railCurrentAmps,
    double mechanicalRegenPowerWatts,
    double regenPowerWatts,
    double energyConsumedKwh,
    double energyRegeneratedKwh,
    String faultCode,
    String instanceState,
    String dataQuality,
    String fmiStatus
) {
}
