package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import java.util.List;

/** Curve-driven Java fallback solver kept equation-compatible with TrainTractionBrake/2.0.0. */
final class VehicleSimulationQueue {

    static final double GRAVITY = 9.81;
    static final double SPEED_FLOOR_METERS_PER_SECOND = 0.5;

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
        double mass = input.trainMassKg();
        if (!Double.isFinite(mass) || mass <= 0 || mass > parameters.formation().hardMassLimitKg()) {
            throw new IllegalArgumentException(
                "trainMassKg must be in (0, " + parameters.formation().hardMassLimitKg() + "]"
            );
        }

        VehicleParameters.Drivetrain drivetrain = parameters.drivetrain();
        VehicleParameters.Curves curves = parameters.curves();
        double motorSpeedRpm = motorSpeedRpm(speed, drivetrain);
        double tractionTorque = interpolateCurve(
            motorSpeedRpm,
            curves.speedRpm(),
            curves.tractionTorqueNmPerMotor()
        );
        double brakeTorque = interpolateCurve(
            motorSpeedRpm,
            curves.speedRpm(),
            curves.brakeTorqueNmPerMotor()
        );
        double curveTractionForce = torqueToTrainForceNewtons(tractionTorque, drivetrain);
        double curveElectricBrakeForce = torqueToTrainForceNewtons(brakeTorque, drivetrain);

        boolean tractionPowerAvailable = input.currentCollectionAvailable()
            && input.railVoltage() > parameters.power().cutoffVoltage()
            && input.powerAvailableWatts() > 0;
        double gridPowerAvailable = tractionPowerAvailable ? input.powerAvailableWatts() : 0;
        double commandForce = curveTractionForce * clamp(input.tractionCommand(), 0, 1);
        double supplyLimitedForce = gridPowerAvailable * drivetrain.tractionTotalEfficiency()
            / Math.max(speed, SPEED_FLOOR_METERS_PER_SECOND);
        double adhesionLimitedForce = clamp(input.adhesionCoefficient(), 0.2, 1.0) * mass * GRAVITY;
        double tractionForce = input.doorClosed() && !input.emergencyBrakeCommand()
            ? Math.min(commandForce, Math.min(supplyLimitedForce, adhesionLimitedForce))
            : 0;

        double brakeForce = input.emergencyBrakeCommand()
            ? mass * parameters.brake().emergencyDecelerationMps2()
            : mass * parameters.brake().serviceDecelerationMps2()
                * clamp(input.brakeCommand(), 0, 1);
        double regenCandidateForce = !input.emergencyBrakeCommand() && brakeForce > 0 && speed > 0
            ? Math.min(brakeForce, curveElectricBrakeForce)
            : 0;
        double regenCandidateMechanicalPower = regenCandidateForce * speed;
        double regenGridMechanicalLimit = input.regenPowerAvailableWatts() > 0
            ? input.regenPowerAvailableWatts() / drivetrain.regenTotalEfficiency()
            : 0;
        double mechanicalRegenPower = Math.min(regenCandidateMechanicalPower, regenGridMechanicalLimit);
        double regenBrakeForce = mechanicalRegenPower <= 0
            ? 0
            : Math.min(
                regenCandidateForce,
                mechanicalRegenPower / Math.max(speed, SPEED_FLOOR_METERS_PER_SECOND)
            );
        mechanicalRegenPower = regenBrakeForce * speed;
        double regenPower = mechanicalRegenPower * drivetrain.regenTotalEfficiency();

        double resistanceForce = parameters.resistance().forceNewtons(
            mass,
            parameters.formation().axleCount(),
            parameters.formation().order().size(),
            speed
        );
        double gradientForce = mass * GRAVITY * input.gradient();
        double acceleration = clamp(
            (tractionForce - brakeForce - resistanceForce - gradientForce) / mass,
            -parameters.brake().emergencyDecelerationMps2(),
            parameters.brake().serviceDecelerationMps2()
        );
        double newSpeed = Math.max(0, speed + acceleration * dt);
        double meanSpeed = (speed + newSpeed) * 0.5;
        double newPosition = input.positionMeters() + meanSpeed * dt;

        // FMU algebraic outputs are observed at the end of the communication step.
        double mechanicalTractionPower = tractionForce * newSpeed;
        double tractionPower = mechanicalTractionPower <= 0
            ? 0
            : Math.min(gridPowerAvailable, mechanicalTractionPower / drivetrain.tractionTotalEfficiency());
        double railCurrent = input.railVoltage() > 1 ? tractionPower / input.railVoltage() : 0;
        String faultCode = resolveFaultCode(input);

        return new VehiclePhysicsOutputDto(
            input.trainId(),
            newPosition,
            newSpeed,
            acceleration,
            tractionForce,
            brakeForce,
            regenBrakeForce,
            motorSpeedRpm,
            tractionTorque,
            brakeTorque,
            Math.max(0, brakeForce - regenBrakeForce),
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

    static double motorSpeedRpm(double speedMetersPerSecond, VehicleParameters.Drivetrain drivetrain) {
        return Math.max(0, speedMetersPerSecond) / drivetrain.wheelRadiusMeters()
            * drivetrain.gearRatio() * 60 / (2 * Math.PI);
    }

    static double torqueToTrainForceNewtons(
        double torqueNmPerMotor,
        VehicleParameters.Drivetrain drivetrain
    ) {
        return Math.max(0, torqueNmPerMotor) * drivetrain.motorCount()
            * drivetrain.gearRatio() / drivetrain.wheelRadiusMeters();
    }

    static double interpolateCurve(double x, List<Double> xGrid, List<Double> yGrid) {
        if (xGrid == null || yGrid == null || xGrid.size() != yGrid.size() || xGrid.isEmpty()) {
            throw new IllegalArgumentException("curve grids must be nonempty and have equal length");
        }
        int last = xGrid.size() - 1;
        if (x <= xGrid.get(0)) {
            return yGrid.get(0);
        }
        if (x >= xGrid.get(last)) {
            return yGrid.get(last);
        }
        for (int index = 0; index < last; index++) {
            double x0 = xGrid.get(index);
            double x1 = xGrid.get(index + 1);
            if (x >= x0 && x < x1) {
                double ratio = (x - x0) / (x1 - x0);
                return yGrid.get(index) + (yGrid.get(index + 1) - yGrid.get(index)) * ratio;
            }
        }
        throw new IllegalStateException("curve interpolation did not find an interval for " + x);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
