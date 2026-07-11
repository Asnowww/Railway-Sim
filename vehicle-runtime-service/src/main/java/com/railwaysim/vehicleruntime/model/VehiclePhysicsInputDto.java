package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record VehiclePhysicsInputDto(
    String trainId,
    String lifecycleCommand,
    String sectionId,
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
    double regenPowerAvailableWatts,
    boolean currentCollectionAvailable,
    boolean doorClosed,
    double adhesionCoefficient,
    double previousEnergyConsumedKwh,
    double previousEnergyRegeneratedKwh,
    @JsonIgnore double deltaSeconds,
    String dynamicsState,
    String dynamicsConstraintReason,
    double stationDistanceMeters,
    double stoppingDistanceMeters
) {
}
