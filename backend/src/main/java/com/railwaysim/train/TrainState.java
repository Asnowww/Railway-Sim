package com.railwaysim.train;

public record TrainState(
    String id,
    String routeId,
    double positionMeters,
    double speedMetersPerSecond,
    double lengthMeters,
    double loadRate,
    String status,
    String operationMode,
    double accelerationMetersPerSecondSquared,
    double tractionForceNewtons,
    double brakeForceNewtons,
    double railCurrentAmps,
    double tractionPowerWatts,
    double regenPowerWatts,
    double energyConsumedKwh,
    double energyRegeneratedKwh,
    String faultCode
) {
}
