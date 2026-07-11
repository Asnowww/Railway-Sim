package com.railwaysim.vehicleruntime.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Validated immutable schema-v2 vehicle calibration loaded from config/train_params.yaml. */
public record VehicleParameters(
    String parameterSchemaVersion,
    String trainType,
    double lengthMeters,
    Formation formation,
    Drivetrain drivetrain,
    Curves curves,
    Resistance resistance,
    Brake brake,
    Power power,
    String parameterSetId,
    String curveSetId,
    Path sourcePath
) {
    public VehicleParameters {
        formation = formation.copy();
        curves = curves.copy();
    }

    public record Formation(
        List<String> order,
        int motorCount,
        int axleCount,
        double hardMassLimitKg,
        LoadCases loadCasesKg,
        Map<String, CarMass> carMassKg
    ) {
        Formation copy() {
            return new Formation(
                List.copyOf(order),
                motorCount,
                axleCount,
                hardMassLimitKg,
                loadCasesKg,
                Map.copyOf(carMassKg)
            );
        }
    }

    public record LoadCases(double aw0, double aw2, double aw3) {
    }

    public record CarMass(double aw0, double aw2, double aw3, double hardLimit) {
    }

    public record Drivetrain(
        int motorCount,
        double gearRatio,
        double wheelRadiusMeters,
        double tractionTotalEfficiency,
        double regenTotalEfficiency
    ) {
    }

    public record Curves(
        List<Double> speedRpm,
        List<Double> tractionTorqueNmPerMotor,
        List<Double> brakeTorqueNmPerMotor,
        List<Double> referenceTractionCurrentAmps,
        List<Double> referenceBrakeCurrentAmps,
        List<Double> referenceTractionMechanicalPowerKwPerMotor,
        List<Double> referenceBrakeMechanicalPowerKwPerMotor,
        double referenceVoltageVolts
    ) {
        Curves copy() {
            return new Curves(
                List.copyOf(speedRpm),
                List.copyOf(tractionTorqueNmPerMotor),
                List.copyOf(brakeTorqueNmPerMotor),
                List.copyOf(referenceTractionCurrentAmps),
                List.copyOf(referenceBrakeCurrentAmps),
                List.copyOf(referenceTractionMechanicalPowerKwPerMotor),
                List.copyOf(referenceBrakeMechanicalPowerKwPerMotor),
                referenceVoltageVolts
            );
        }

        public int pointCount() {
            return speedRpm.size();
        }
    }

    public record Resistance(
        double davisMassCoefficient,
        double davisAxleConstant,
        double davisSpeedMassCoefficient,
        double davisAeroBase,
        double davisAeroVehicleCoefficient,
        double frontalAreaSquareMeters
    ) {
        public double forceNewtons(double trainMassKg, int axleCount, int vehicleCount, double speedMetersPerSecond) {
            double massTonnes = trainMassKg / 1000.0;
            double speedKph = Math.max(0, speedMetersPerSecond) * 3.6;
            return davisMassCoefficient * massTonnes
                + davisAxleConstant * axleCount
                + davisSpeedMassCoefficient * massTonnes * speedKph
                + (davisAeroBase + davisAeroVehicleCoefficient * (vehicleCount - 1))
                    * frontalAreaSquareMeters * speedKph * speedKph;
        }
    }

    public record Brake(double serviceDecelerationMps2, double emergencyDecelerationMps2) {
    }

    public record Power(double nominalVoltage, double minVoltage, double cutoffVoltage) {
    }

    public double emptyMassKg() {
        return formation.loadCasesKg().aw0();
    }

    /** Rated AW2 passenger/load mass above AW0. */
    public double maxLoadMassKg() {
        return formation.loadCasesKg().aw2() - formation.loadCasesKg().aw0();
    }

    public double maxOperatingLoadMassKg() {
        return formation.loadCasesKg().aw3() - formation.loadCasesKg().aw0();
    }

    public double hardLoadMassLimitKg() {
        return formation.hardMassLimitKg() - formation.loadCasesKg().aw0();
    }

    public double maxCurveMechanicalTractionPowerWatts() {
        return curves.referenceTractionMechanicalPowerKwPerMotor().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0) * 1000 * drivetrain.motorCount();
    }

    public double maxCurveTractionForceNewtons() {
        return curves.tractionTorqueNmPerMotor().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0) * drivetrain.motorCount() * drivetrain.gearRatio() / drivetrain.wheelRadiusMeters();
    }

    public double fullMechanicalPowerGridDemandWatts() {
        return maxCurveMechanicalTractionPowerWatts() / drivetrain.tractionTotalEfficiency();
    }
}
