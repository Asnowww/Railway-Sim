package com.railwaysim.train;

import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsOutput;

public class TrainEntity {

    private final String id;
    private final String routeId;
    private final double lengthMeters;
    private double positionMeters;
    private double speedMetersPerSecond;
    private double loadRate;
    private String status = "RUNNING";
    private String operationMode = "ATO";
    private double accelerationMetersPerSecondSquared;
    private double tractionForceNewtons;
    private double brakeForceNewtons;
    private double railCurrentAmps;
    private double tractionPowerWatts;
    private double regenPowerWatts;
    private double energyConsumedKwh;
    private double energyRegeneratedKwh;
    private String faultCode = "OK";

    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters) {
        this(id, routeId, positionMeters, lengthMeters, 0.35);
    }

    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters, double loadRate) {
        this.id = id;
        this.routeId = routeId;
        this.positionMeters = positionMeters;
        this.lengthMeters = lengthMeters;
        this.loadRate = loadRate;
    }

    public void applyPhysicsOutput(VehiclePhysicsOutput output, TrainStateReport report) {
        positionMeters = Math.max(0, output.newPositionMeters());
        speedMetersPerSecond = Math.max(0, output.newSpeedMetersPerSecond());
        accelerationMetersPerSecondSquared = output.accelerationMetersPerSecondSquared();
        tractionForceNewtons = output.tractionForceNewtons();
        brakeForceNewtons = output.brakeForceNewtons();
        railCurrentAmps = output.railCurrentAmps();
        tractionPowerWatts = output.tractionPowerWatts();
        regenPowerWatts = output.regenPowerWatts();
        energyConsumedKwh = output.energyConsumedKwh();
        energyRegeneratedKwh = output.energyRegeneratedKwh();
        faultCode = output.faultCode();
        operationMode = report.operationMode();
        status = report.emergencyBrakeCommand() ? "EMERGENCY_BRAKE" : "OK".equals(output.faultCode()) ? "RUNNING" : "DEGRADED";
    }

    public TrainState state() {
        return new TrainState(
            id,
            routeId,
            positionMeters,
            speedMetersPerSecond,
            lengthMeters,
            loadRate,
            status,
            operationMode,
            accelerationMetersPerSecondSquared,
            tractionForceNewtons,
            brakeForceNewtons,
            railCurrentAmps,
            tractionPowerWatts,
            regenPowerWatts,
            energyConsumedKwh,
            energyRegeneratedKwh,
            faultCode
        );
    }
}
