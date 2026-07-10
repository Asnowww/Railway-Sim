package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;

/**
 * 仿真队列负责推进车辆纵向动力学，首期移植中央 Java fallback 模型。
 */
final class VehicleSimulationQueue {

    private static final double GRAVITY = 9.81;
    private static final double MAX_TRACTION_POWER_WATTS = 3_200_000;
    private static final double MAX_TRACTION_FORCE_NEWTONS = 240_000;
    private static final double MAX_SERVICE_BRAKE_FORCE_NEWTONS = 220_000;
    private static final double MAX_EMERGENCY_BRAKE_FORCE_NEWTONS = 300_000;
    private static final double TRACTION_EFFICIENCY = 0.88;
    private static final double REGEN_EFFICIENCY = 0.35;
    private static final double REGEN_BRAKE_RATIO = 0.45;
    private static final double DAVIS_A = 1_800;
    private static final double DAVIS_B = 45;
    private static final double DAVIS_C = 3.2;
    private static final double MINIMUM_VOLTAGE = 1_000;
    private static final double CUTOFF_VOLTAGE = 900;

    private final VehicleRuntimeQueue queue;

    VehicleSimulationQueue(VehicleRuntimeProperties properties) {
        this.queue = new VehicleRuntimeQueue(properties.getQueueCapacity());
    }

    VehiclePhysicsOutputDto step(long tick, VehiclePhysicsInputDto input) {
        return queue.execute(tick, () -> solve(input));
    }

    private VehiclePhysicsOutputDto solve(VehiclePhysicsInputDto input) {
        double dt = Math.max(input.deltaSeconds(), 0.001);
        double speed = Math.max(input.speedMetersPerSecond(), 0);
        double mass = Math.max(input.trainMassKg(), 1);
        double powerFactor = input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0
            ? 0
            : clamp(input.powerAvailableWatts() / MAX_TRACTION_POWER_WATTS, 0, 1);
        double adhesionFactor = clamp(input.adhesionCoefficient(), 0.2, 1.0);

        double tractionForce = MAX_TRACTION_FORCE_NEWTONS * clamp(input.tractionCommand(), 0, 1) * powerFactor * adhesionFactor;
        double brakeForce = input.emergencyBrakeCommand()
            ? MAX_EMERGENCY_BRAKE_FORCE_NEWTONS
            : MAX_SERVICE_BRAKE_FORCE_NEWTONS * clamp(input.brakeCommand(), 0, 1);

        double resistanceForce = DAVIS_A + DAVIS_B * speed + DAVIS_C * speed * speed;
        double gradientForce = mass * GRAVITY * input.gradient();
        double acceleration = clamp((tractionForce - brakeForce - resistanceForce - gradientForce) / mass, -1.3, 1.0);
        double newSpeed = Math.max(0, speed + acceleration * dt);
        double newPosition = input.positionMeters() + (speed + newSpeed) * 0.5 * dt;
        double tractionPower = tractionForce <= 0
            ? 0
            : Math.min(tractionForce * Math.max(newSpeed, 0.1) / TRACTION_EFFICIENCY, input.powerAvailableWatts());
        double railCurrent = input.railVoltage() > 1 ? tractionPower / input.railVoltage() : 0;
        double regenBrakeForce = brakeForce > 0 && speed > 0 ? brakeForce * REGEN_BRAKE_RATIO : 0;
        double regenPower = input.railVoltage() > CUTOFF_VOLTAGE ? regenBrakeForce * speed * REGEN_EFFICIENCY : 0;

        return new VehiclePhysicsOutputDto(
            input.trainId(),
            newPosition,
            newSpeed,
            acceleration,
            tractionForce,
            brakeForce,
            regenBrakeForce,
            tractionPower * TRACTION_EFFICIENCY,
            tractionPower,
            railCurrent,
            REGEN_EFFICIENCY > 0 ? regenPower / REGEN_EFFICIENCY : 0,
            regenPower,
            input.previousEnergyConsumedKwh() + tractionPower * dt / 3_600_000,
            input.previousEnergyRegeneratedKwh() + regenPower * dt / 3_600_000,
            resolveFaultCode(input),
            "ACTIVE",
            "GOOD",
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
        if (input.railVoltage() <= CUTOFF_VOLTAGE || input.powerAvailableWatts() <= 0) {
            return "CURRENT_COLLECTION_LOST";
        }
        if (input.railVoltage() < MINIMUM_VOLTAGE) {
            return "LOW_VOLTAGE";
        }
        return "OK";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
