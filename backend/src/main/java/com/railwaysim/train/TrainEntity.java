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
    private String dynamicsState = "COASTING";
    private String dynamicsConstraintReason = "INITIAL";
    private double speedLimitMetersPerSecond;
    private double movementAuthorityDistanceMeters;
    private double stationDistanceMeters;
    private double stoppingDistanceMeters;
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
    private String injectedFaultCode;

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
        dynamicsState = report.dynamicsState();
        dynamicsConstraintReason = report.dynamicsConstraintReason();
        speedLimitMetersPerSecond = report.speedLimitMetersPerSecond();
        movementAuthorityDistanceMeters = report.movementAuthorityDistanceMeters();
        stationDistanceMeters = report.stationDistanceMeters();
        stoppingDistanceMeters = report.stoppingDistanceMeters();
        status = resolveStatus(report, output);
    }

    public TrainState state() {
        String effectiveDoorState = effectiveDoorState();
        String effectiveTractionState = effectiveTractionState();
        String effectiveBrakeState = effectiveBrakeState();
        String effectiveCurrentCollectionStatus = effectiveCurrentCollectionStatus();
        boolean effectiveTractionAvailable = effectiveTractionAvailable();
        boolean effectiveBrakeAvailable = effectiveBrakeAvailable();
        String effectiveSelfCheckStatus = injectedFaultCode == null ? selfCheckStatus : "FAIL";
        int effectiveFaultLevel = injectedFaultCode == null ? faultLevel : 3;
        String effectiveAvailableOperationMode = injectedFaultCode == null ? availableOperationMode : "NO_DEPARTURE";
        String effectiveDataQuality = injectedFaultCode == null ? dataQuality : "INVALID";
        String effectiveFaultCode = injectedFaultCode == null ? faultCode : injectedFaultCode;
        String effectiveStatus = injectedFaultCode == null ? status : "FAULT";
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
            effectiveStatus,
            operationMode,
            speedMetersPerSecond <= 0.05,
            effectiveDoorState,
            effectiveTractionState,
            effectiveBrakeState,
            effectiveCurrentCollectionStatus,
            effectiveTractionAvailable,
            effectiveBrakeAvailable,
            effectiveSelfCheckStatus,
            effectiveFaultLevel,
            effectiveAvailableOperationMode,
            effectiveDataQuality,
            dynamicsState,
            dynamicsConstraintReason,
            speedLimitMetersPerSecond,
            movementAuthorityDistanceMeters,
            stationDistanceMeters,
            stoppingDistanceMeters,
            accelerationMetersPerSecondSquared,
            tractionForceNewtons,
            brakeForceNewtons,
            regenBrakeForceNewtons,
            railCurrentAmps,
            tractionPowerWatts,
            regenPowerWatts,
            energyConsumedKwh,
            energyRegeneratedKwh,
            effectiveFaultCode
        );
    }

    public void injectFault(String faultCode) {
        injectedFaultCode = faultCode == null || faultCode.isBlank() ? "TRAIN_FAULT" : faultCode;
    }

    public void clearFault() {
        injectedFaultCode = null;
    }

    public String injectedFaultCode() {
        return injectedFaultCode;
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

    private String effectiveDoorState() {
        return "DOOR_NOT_LOCKED".equals(injectedFaultCode) ? "OPEN" : doorState;
    }

    private String effectiveTractionState() {
        return "TRACTION_UNAVAILABLE".equals(injectedFaultCode) ? "UNAVAILABLE" : tractionState;
    }

    private String effectiveBrakeState() {
        return "BRAKE_UNAVAILABLE".equals(injectedFaultCode) ? "UNAVAILABLE" : brakeState;
    }

    private String effectiveCurrentCollectionStatus() {
        return "CURRENT_COLLECTION_LOST".equals(injectedFaultCode) ? "LOST" : currentCollectionStatus;
    }

    private boolean effectiveTractionAvailable() {
        return injectedFaultCode == null || !"TRACTION_UNAVAILABLE".equals(injectedFaultCode);
    }

    private boolean effectiveBrakeAvailable() {
        return injectedFaultCode == null || !"BRAKE_UNAVAILABLE".equals(injectedFaultCode);
    }
}
