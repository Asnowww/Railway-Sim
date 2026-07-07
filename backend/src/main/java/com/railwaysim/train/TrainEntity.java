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
    private String doorState = "CLOSED_LOCKED";
    private String tractionState = "IDLE";
    private String brakeState = "RELEASED";
    private String currentCollectionStatus = "NORMAL";
    private boolean tractionAvailable = true;
    private boolean brakeAvailable = true;
    private String selfCheckStatus = "PASS";
    private int faultLevel;
    private String availableOperationMode = "NORMAL";
    private String dataQuality = "GOOD";
    private double accelerationMetersPerSecondSquared;
    private double tractionForceNewtons;
    private double brakeForceNewtons;
    private double regenBrakeForceNewtons;
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
        regenBrakeForceNewtons = output.regenBrakeForceNewtons();
        railCurrentAmps = output.railCurrentAmps();
        tractionPowerWatts = output.tractionPowerWatts();
        regenPowerWatts = output.regenPowerWatts();
        energyConsumedKwh = output.energyConsumedKwh();
        energyRegeneratedKwh = output.energyRegeneratedKwh();
        faultCode = output.faultCode();
        operationMode = report.operationMode();
        doorState = report.doorState();
        tractionState = report.tractionState();
        brakeState = report.brakeState();
        currentCollectionStatus = report.currentCollectionStatus();
        tractionAvailable = report.tractionAvailable();
        brakeAvailable = report.brakeAvailable();
        selfCheckStatus = report.selfCheckStatus();
        faultLevel = report.faultLevel();
        availableOperationMode = report.availableOperationMode();
        dataQuality = report.dataQuality();
        status = resolveStatus(report, output);
    }

    public TrainState state() {
        return new TrainState(
            id,
            routeId,
            id,
            positionMeters,
            speedMetersPerSecond,
            lengthMeters,
            positionMeters,
            Math.max(0, positionMeters - lengthMeters),
            loadRate,
            status,
            operationMode,
            speedMetersPerSecond <= 0.05,
            doorState,
            tractionState,
            brakeState,
            currentCollectionStatus,
            tractionAvailable,
            brakeAvailable,
            selfCheckStatus,
            faultLevel,
            availableOperationMode,
            dataQuality,
            accelerationMetersPerSecondSquared,
            tractionForceNewtons,
            brakeForceNewtons,
            regenBrakeForceNewtons,
            railCurrentAmps,
            tractionPowerWatts,
            regenPowerWatts,
            energyConsumedKwh,
            energyRegeneratedKwh,
            faultCode
        );
    }

    private String resolveStatus(TrainStateReport report, VehiclePhysicsOutput output) {
        if (report.emergencyBrakeCommand()) {
            return "EMERGENCY_BRAKE";
        }
        if (report.faultLevel() >= 3) {
            return "FAULT";
        }
        if (report.faultLevel() > 0 || !"OK".equals(output.faultCode())) {
            return "DEGRADED";
        }
        return "RUNNING";
    }
}
