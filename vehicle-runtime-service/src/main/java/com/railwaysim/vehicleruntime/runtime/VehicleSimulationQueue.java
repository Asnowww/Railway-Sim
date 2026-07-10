package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;

/** Java fallback solver backed by the same YAML calibration that initializes the future FMU. */
final class VehicleSimulationQueue {

    private static final double GRAVITY = 9.81;
    private static final double SPEED_FLOOR_METERS_PER_SECOND = 0.5;

    private final VehicleRuntimeQueue queue;
    private final VehicleParameters parameters;

    VehicleSimulationQueue(VehicleRuntimeProperties properties, VehicleParameters parameters) {
        this.queue = new VehicleRuntimeQueue(properties.getQueueCapacity());
        this.parameters = parameters;
    }

    VehiclePhysicsOutputDto step(long tick, VehiclePhysicsInputDto input) {
        return queue.execute(tick, () -> solve(input));
    }

    private VehiclePhysicsOutputDto solve(VehiclePhysicsInputDto input) {
        double dt = Math.max(input.deltaSeconds(), 0.001);
        double speed = Math.max(input.speedMetersPerSecond(), 0);
        double mass = Math.max(input.trainMassKg(), 1);
        double tractionEfficiency = parameters.traction().efficiency();
        boolean tractionPowerAvailable = input.currentCollectionAvailable()
            && input.railVoltage() > parameters.power().cutoffVoltage()
            && input.powerAvailableWatts() > 0;
        double gridPowerAvailable = tractionPowerAvailable ? input.powerAvailableWatts() : 0;
        double mechanicalPowerLimit = Math.min(
            parameters.traction().maxPowerWatts(),
            gridPowerAvailable * tractionEfficiency
        );
        double commandForce = parameters.traction().maxTractionForceNewtons()
            * clamp(input.tractionCommand(), 0, 1);
        double powerLimitedForce = mechanicalPowerLimit / Math.max(speed, SPEED_FLOOR_METERS_PER_SECOND);
        double adhesionLimitedForce = clamp(input.adhesionCoefficient(), 0.2, 1.0) * mass * GRAVITY;
        double tractionForce = input.doorClosed() && !input.emergencyBrakeCommand()
            ? Math.min(commandForce, Math.min(powerLimitedForce, adhesionLimitedForce))
            : 0;

        double brakeForce = input.emergencyBrakeCommand()
            ? parameters.brake().maxEmergencyBrakeForceNewtons()
            : parameters.brake().maxServiceBrakeForceNewtons() * clamp(input.brakeCommand(), 0, 1);
        double resistanceForce = parameters.resistance().davisA()
            + parameters.resistance().davisB() * speed
            + parameters.resistance().davisC() * speed * speed;
        double gradientForce = mass * GRAVITY * input.gradient();
        double acceleration = clamp((tractionForce - brakeForce - resistanceForce - gradientForce) / mass, -1.3, 1.0);
        double newSpeed = Math.max(0, speed + acceleration * dt);
        double meanSpeed = (speed + newSpeed) * 0.5;
        double newPosition = input.positionMeters() + meanSpeed * dt;

        double mechanicalTractionPower = Math.min(mechanicalPowerLimit, tractionForce * meanSpeed);
        double tractionPower = mechanicalTractionPower <= 0
            ? 0
            : Math.min(gridPowerAvailable, mechanicalTractionPower / tractionEfficiency);
        double railCurrent = input.railVoltage() > 1 ? tractionPower / input.railVoltage() : 0;

        double regenCandidateForce = brakeForce > 0 && speed > 0
            ? brakeForce * parameters.brake().regenBrakeRatio()
            : 0;
        double regenCandidateMechanicalPower = regenCandidateForce * speed;
        double regenGridMechanicalLimit = input.regenPowerAvailableWatts() > 0
            ? input.regenPowerAvailableWatts() / parameters.brake().regenEfficiency()
            : 0;
        double mechanicalRegenPower = Math.min(
            Math.min(regenCandidateMechanicalPower, parameters.traction().maxPowerWatts()),
            regenGridMechanicalLimit
        );
        double regenBrakeForce = mechanicalRegenPower <= 0
            ? 0
            : Math.min(regenCandidateForce, mechanicalRegenPower / Math.max(speed, SPEED_FLOOR_METERS_PER_SECOND));
        mechanicalRegenPower = regenBrakeForce * speed;
        double regenPower = mechanicalRegenPower * parameters.brake().regenEfficiency();
        String faultCode = resolveFaultCode(input);

        return new VehiclePhysicsOutputDto(
            input.trainId(),
            newPosition,
            newSpeed,
            acceleration,
            tractionForce,
            brakeForce,
            regenBrakeForce,
            mechanicalTractionPower,
            tractionPower,
            railCurrent,
            mechanicalRegenPower,
            regenPower,
            input.previousEnergyConsumedKwh() + tractionPower * dt / 3_600_000,
            input.previousEnergyRegeneratedKwh() + regenPower * dt / 3_600_000,
            faultCode,
            "ACTIVE",
            "OK".equals(faultCode) ? "GOOD" : "DEGRADED",
            "OK"
        );
    }

    private String resolveFaultCode(VehiclePhysicsInputDto input) {
        if (!input.doorClosed()) {
            return "DOOR_NOT_LOCKED";
        }
        if (input.emergencyBrakeCommand()) {
            return "ATP_BRAKE";
        }
        if (!input.currentCollectionAvailable()
            || input.railVoltage() <= parameters.power().cutoffVoltage()
            || input.powerAvailableWatts() <= 0) {
            return "CURRENT_COLLECTION_LOST";
        }
        if (input.railVoltage() < parameters.power().minVoltage()) {
            return "LOW_VOLTAGE";
        }
        return "OK";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
