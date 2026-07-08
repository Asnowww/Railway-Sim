package com.railwaysim.vehicle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class SimpleVehicleDynamicsModel {

    private static final double GRAVITY = 9.81;
    private final TrainDynamicsParameters parameters;

    public SimpleVehicleDynamicsModel() {
        this.parameters = loadParameters();
    }

    public VehiclePhysicsOutput step(VehiclePhysicsInput input) {
        double dt = Math.max(input.deltaSeconds(), 0.001);
        double speed = Math.max(input.speedMetersPerSecond(), 0);
        double mass = Math.max(input.trainMassKg(), 1);

        double powerFactor = input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0
            ? 0
            : clamp(input.powerAvailableWatts() / parameters.maxTractionPowerWatts(), 0, 1);
        double adhesionFactor = clamp(input.adhesionCoefficient(), 0.2, 1.0);

        double tractionForce = parameters.maxTractionForceNewtons()
            * clamp(input.tractionCommand(), 0, 1)
            * powerFactor
            * adhesionFactor;
        double brakeForce = input.emergencyBrakeCommand()
            ? parameters.maxEmergencyBrakeForceNewtons()
            : parameters.maxServiceBrakeForceNewtons() * clamp(input.brakeCommand(), 0, 1);

        double resistanceForce = parameters.davisA() + parameters.davisB() * speed + parameters.davisC() * speed * speed;
        double gradientForce = mass * GRAVITY * input.gradient();
        double netForce = tractionForce - brakeForce - resistanceForce - gradientForce;
        double acceleration = clamp(netForce / mass, -1.3, 1.0);

        double newSpeed = Math.max(0, speed + acceleration * dt);
        double averageSpeed = (speed + newSpeed) / 2.0;
        double newPosition = input.positionMeters() + averageSpeed * dt;

        double tractionPower = tractionForce <= 0
            ? 0
            : Math.min(tractionForce * Math.max(newSpeed, 0.1) / parameters.tractionEfficiency(), input.powerAvailableWatts());
        double railCurrent = input.railVoltage() > 1 ? tractionPower / input.railVoltage() : 0;

        double regenBrakeForce = brakeForce > 0 && speed > 0 ? brakeForce * parameters.regenBrakeRatio() : 0;
        double regenPower = input.railVoltage() > parameters.cutoffVoltage()
            ? regenBrakeForce * speed * parameters.regenEfficiency()
            : 0;

        double energyConsumed = input.previousEnergyConsumedKwh() + tractionPower * dt / 3_600_000;
        double energyRegenerated = input.previousEnergyRegeneratedKwh() + regenPower * dt / 3_600_000;
        String faultCode = resolveFaultCode(input);

        return new VehiclePhysicsOutput(
            input.trainId(),
            newPosition,
            newSpeed,
            acceleration,
            tractionForce,
            brakeForce,
            regenBrakeForce,
            tractionPower,
            railCurrent,
            regenPower,
            energyConsumed,
            energyRegenerated,
            faultCode
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String resolveFaultCode(VehiclePhysicsInput input) {
        if (!input.doorClosed()) {
            return "DOOR_NOT_LOCKED";
        }
        if (input.emergencyBrakeCommand()) {
            return "ATP_BRAKE";
        }
        if (input.railVoltage() <= parameters.cutoffVoltage() || input.powerAvailableWatts() <= 0) {
            return "CURRENT_COLLECTION_LOST";
        }
        if (input.railVoltage() < parameters.minimumVoltage()) {
            return "LOW_VOLTAGE";
        }
        return "OK";
    }

    private TrainDynamicsParameters loadParameters() {
        for (Path candidate : new Path[] {
            Path.of("../config/train_params.yaml"),
            Path.of("config/train_params.yaml")
        }) {
            if (Files.exists(candidate)) {
                try {
                    TrainParamsFile paramsFile = new ObjectMapper(new YAMLFactory())
                        .readValue(Files.readString(candidate), TrainParamsFile.class);
                    return TrainDynamicsParameters.from(paramsFile);
                } catch (IOException ignored) {
                    return TrainDynamicsParameters.defaults();
                }
            }
        }
        return TrainDynamicsParameters.defaults();
    }

    private record TrainDynamicsParameters(
        double maxTractionPowerWatts,
        double maxTractionForceNewtons,
        double maxServiceBrakeForceNewtons,
        double maxEmergencyBrakeForceNewtons,
        double tractionEfficiency,
        double regenEfficiency,
        double regenBrakeRatio,
        double davisA,
        double davisB,
        double davisC,
        double minimumVoltage,
        double cutoffVoltage
    ) {
        static TrainDynamicsParameters defaults() {
            return new TrainDynamicsParameters(
                3_200_000,
                240_000,
                220_000,
                300_000,
                0.88,
                0.35,
                0.45,
                1_800,
                45,
                3.2,
                1_000,
                900
            );
        }

        static TrainDynamicsParameters from(TrainParamsFile paramsFile) {
            TrainDynamicsParameters defaults = defaults();
            return new TrainDynamicsParameters(
                paramsFile.traction == null || paramsFile.traction.maxPowerWatts <= 0
                    ? defaults.maxTractionPowerWatts()
                    : paramsFile.traction.maxPowerWatts,
                paramsFile.traction == null || paramsFile.traction.maxTractionForceNewtons <= 0
                    ? defaults.maxTractionForceNewtons()
                    : paramsFile.traction.maxTractionForceNewtons,
                paramsFile.brake == null || paramsFile.brake.maxServiceBrakeForceNewtons <= 0
                    ? defaults.maxServiceBrakeForceNewtons()
                    : paramsFile.brake.maxServiceBrakeForceNewtons,
                paramsFile.brake == null || paramsFile.brake.maxEmergencyBrakeForceNewtons <= 0
                    ? defaults.maxEmergencyBrakeForceNewtons()
                    : paramsFile.brake.maxEmergencyBrakeForceNewtons,
                paramsFile.traction == null || paramsFile.traction.efficiency <= 0
                    ? defaults.tractionEfficiency()
                    : paramsFile.traction.efficiency,
                paramsFile.brake == null || paramsFile.brake.regenEfficiency <= 0
                    ? defaults.regenEfficiency()
                    : paramsFile.brake.regenEfficiency,
                paramsFile.brake == null || paramsFile.brake.regenBrakeRatio <= 0
                    ? defaults.regenBrakeRatio()
                    : paramsFile.brake.regenBrakeRatio,
                paramsFile.resistance == null || paramsFile.resistance.davisA <= 0
                    ? defaults.davisA()
                    : paramsFile.resistance.davisA,
                paramsFile.resistance == null || paramsFile.resistance.davisB <= 0
                    ? defaults.davisB()
                    : paramsFile.resistance.davisB,
                paramsFile.resistance == null || paramsFile.resistance.davisC <= 0
                    ? defaults.davisC()
                    : paramsFile.resistance.davisC,
                paramsFile.power == null || paramsFile.power.minVoltage <= 0
                    ? defaults.minimumVoltage()
                    : paramsFile.power.minVoltage,
                paramsFile.power == null || paramsFile.power.cutoffVoltage <= 0
                    ? defaults.cutoffVoltage()
                    : paramsFile.power.cutoffVoltage
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class TrainParamsFile {
        public Traction traction;
        public Brake brake;
        public Resistance resistance;
        public Power power;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Traction {
        public double maxPowerWatts;
        public double maxTractionForceNewtons;
        public double efficiency;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Brake {
        public double maxServiceBrakeForceNewtons;
        public double maxEmergencyBrakeForceNewtons;
        public double regenBrakeRatio;
        public double regenEfficiency;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Resistance {
        public double davisA;
        public double davisB;
        public double davisC;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Power {
        public double minVoltage;
        public double cutoffVoltage;
    }
}
