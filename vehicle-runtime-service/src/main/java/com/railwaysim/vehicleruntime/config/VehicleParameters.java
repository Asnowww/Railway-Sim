package com.railwaysim.vehicleruntime.config;

import java.nio.file.Path;

/** Validated immutable vehicle calibration loaded from config/train_params.yaml. */
public record VehicleParameters(
    String trainType,
    double lengthMeters,
    double emptyMassKg,
    double maxLoadMassKg,
    Traction traction,
    Brake brake,
    Resistance resistance,
    Power power,
    String parameterSetId,
    Path sourcePath
) {
    public record Traction(
        double maxPowerWatts,
        double maxTractionForceNewtons,
        double efficiency
    ) {
    }

    public record Brake(
        double maxServiceBrakeForceNewtons,
        double maxEmergencyBrakeForceNewtons,
        double regenBrakeRatio,
        double regenEfficiency
    ) {
    }

    public record Resistance(
        double davisA,
        double davisB,
        double davisC
    ) {
    }

    public record Power(
        double nominalVoltage,
        double minVoltage,
        double cutoffVoltage
    ) {
    }

    public double fullMechanicalPowerGridDemandWatts() {
        return traction.maxPowerWatts() / traction.efficiency();
    }
}
