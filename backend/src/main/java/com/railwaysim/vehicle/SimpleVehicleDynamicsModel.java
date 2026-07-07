package com.railwaysim.vehicle;

import org.springframework.stereotype.Component;

@Component
public class SimpleVehicleDynamicsModel {

    private static final double MAX_TRACTION_FORCE_NEWTONS = 240_000;
    private static final double MAX_SERVICE_BRAKE_FORCE_NEWTONS = 220_000;
    private static final double MAX_EMERGENCY_BRAKE_FORCE_NEWTONS = 300_000;
    private static final double NOMINAL_POWER_WATTS = 3_200_000;
    private static final double TRACTION_EFFICIENCY = 0.88;
    private static final double REGEN_EFFICIENCY = 0.35;
    private static final double GRAVITY = 9.81;

    public VehiclePhysicsOutput step(VehiclePhysicsInput input) {
        double dt = Math.max(input.deltaSeconds(), 0.001);
        double speed = Math.max(input.speedMetersPerSecond(), 0);
        double mass = Math.max(input.trainMassKg(), 1);

        double powerFactor = input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0
            ? 0
            : clamp(input.powerAvailableWatts() / NOMINAL_POWER_WATTS, 0, 1);
        double adhesionFactor = clamp(input.adhesionCoefficient(), 0.2, 1.0);

        double tractionForce = MAX_TRACTION_FORCE_NEWTONS
            * clamp(input.tractionCommand(), 0, 1)
            * powerFactor
            * adhesionFactor;
        double brakeForce = input.emergencyBrakeCommand()
            ? MAX_EMERGENCY_BRAKE_FORCE_NEWTONS
            : MAX_SERVICE_BRAKE_FORCE_NEWTONS * clamp(input.brakeCommand(), 0, 1);

        double resistanceForce = 1_800 + 45 * speed + 3.2 * speed * speed;
        double gradientForce = mass * GRAVITY * input.gradient();
        double netForce = tractionForce - brakeForce - resistanceForce - gradientForce;
        double acceleration = clamp(netForce / mass, -1.3, 1.0);

        double newSpeed = Math.max(0, speed + acceleration * dt);
        double averageSpeed = (speed + newSpeed) / 2.0;
        double newPosition = input.positionMeters() + averageSpeed * dt;

        double tractionPower = tractionForce <= 0
            ? 0
            : Math.min(tractionForce * Math.max(newSpeed, 0.1) / TRACTION_EFFICIENCY, input.powerAvailableWatts());
        double railCurrent = input.railVoltage() > 1 ? tractionPower / input.railVoltage() : 0;

        double regenBrakeForce = brakeForce > 0 && speed > 0 ? brakeForce * 0.45 : 0;
        double regenPower = regenBrakeForce * speed * REGEN_EFFICIENCY;

        double energyConsumed = input.previousEnergyConsumedKwh() + tractionPower * dt / 3_600_000;
        double energyRegenerated = input.previousEnergyRegeneratedKwh() + regenPower * dt / 3_600_000;
        String faultCode = input.railVoltage() <= 0 ? "THIRD_RAIL_DEENERGIZED" : "OK";

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
}
