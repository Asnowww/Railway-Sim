package com.railwaysim.train;

import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehicleLoadPolicy;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;

public class TrainEntity {

    private final String id;
    private final String routeId;
    private final double lengthMeters;
    private double positionMeters;
    private double speedMetersPerSecond;
    private double loadRate;
    private double loadMassKg;
    private String overloadStatus = "NORMAL";
    private int availableTractionCount = VehicleLoadPolicy.NOMINAL_TRACTION_UNITS;
    private int availableBrakeCount = VehicleLoadPolicy.NOMINAL_BRAKE_UNITS;
    private String vehicleProtectionReason = "NONE";
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
    private double vehicleFaultSpeedLimitMetersPerSecond;
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
        this.loadMassKg = VehicleLoadPolicy.loadMassFromRate(loadRate);
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
        loadMassKg = report.loadMassKg();
        loadRate = VehicleLoadPolicy.loadRateFromMass(loadMassKg);
        overloadStatus = report.overloadStatus();
        availableTractionCount = report.availableTractionCount();
        availableBrakeCount = report.availableBrakeCount();
        vehicleProtectionReason = report.vehicleProtectionReason();
        dynamicsState = report.dynamicsState();
        dynamicsConstraintReason = report.dynamicsConstraintReason();
        speedLimitMetersPerSecond = report.speedLimitMetersPerSecond();
        movementAuthorityDistanceMeters = report.movementAuthorityDistanceMeters();
        stationDistanceMeters = report.stationDistanceMeters();
        stoppingDistanceMeters = report.stoppingDistanceMeters();
        status = resolveStatus(report, output);
    }

    public TrainState state() {
        return state(null);
    }

    public TrainState state(ExternalTrainControlSession controlSession) {
        double effectiveLoadMassKg = VehicleLoadPolicy.loadMassKg(loadMassKg, loadRate);
        double effectiveLoadRate = VehicleLoadPolicy.loadRateFromMass(effectiveLoadMassKg);
        String effectiveOverloadStatus = VehicleLoadPolicy.overloadStatus(effectiveLoadMassKg);
        String effectiveDoorState = effectiveDoorState();
        String effectiveTractionState = effectiveTractionState();
        String effectiveBrakeState = effectiveBrakeState();
        String effectiveCurrentCollectionStatus = effectiveCurrentCollectionStatus();
        boolean effectiveTractionAvailable = effectiveTractionAvailable();
        boolean effectiveBrakeAvailable = effectiveBrakeAvailable();
        int effectiveAvailableTractionCount = effectiveTractionAvailable ? availableTractionCount : 0;
        int effectiveAvailableBrakeCount = effectiveBrakeAvailable ? availableBrakeCount : 0;
        String effectiveSelfCheckStatus = injectedFaultCode == null ? selfCheckStatus : "FAIL";
        int effectiveFaultLevel = injectedFaultCode == null ? faultLevel : 3;
        String overloadVehicleProtectionReason = VehicleLoadPolicy.vehicleProtectionReason(effectiveOverloadStatus);
        String effectiveVehicleProtectionReason = injectedFaultCode == null
            ? mergeVehicleProtectionReason(vehicleProtectionReason, overloadVehicleProtectionReason)
            : injectedFaultCode;
        String effectiveAvailableOperationMode = injectedFaultCode == null
            ? resolveAvailableOperationMode(availableOperationMode, effectiveVehicleProtectionReason)
            : "NO_DEPARTURE";
        String effectiveDataQuality = injectedFaultCode == null ? dataQuality : "INVALID";
        String effectiveFaultCode = injectedFaultCode == null ? faultCode : injectedFaultCode;
        String effectiveStatus = injectedFaultCode == null ? status : "FAULT";
        return new TrainState(
            id,
            routeId,
            id,
            controlSession == null ? ExternalTrainControlSessionState.IN_SERVICE.name() : controlSession.state().name(),
            controlSession == null ? "ATTACHED" : controlSession.signalNetworkStatus(),
            controlSession == null ? "ATTACHED" : controlSession.powerNetworkStatus(),
            controlSession == null ? "EXTERNAL_CONTROL_IN_SERVICE" : controlSession.reason(),
            controlSession == null ? 0 : controlSession.linkId(),
            controlSession == null ? "UNKNOWN" : controlSession.direction().name(),
            positionMeters,
            speedMetersPerSecond,
            lengthMeters,
            positionMeters,
            Math.max(0, positionMeters - lengthMeters),
            effectiveLoadRate,
            effectiveLoadMassKg,
            effectiveOverloadStatus,
            effectiveAvailableTractionCount,
            effectiveAvailableBrakeCount,
            effectiveVehicleProtectionReason,
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
            vehicleFaultSpeedLimitMetersPerSecond,
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

    public void applyOperationalTelemetry(TrainOperationalTelemetry telemetry) {
        positionMeters = Math.max(0, telemetry.cumulativeDistanceMeters());
        speedMetersPerSecond = Math.max(0, telemetry.speedMetersPerSecond());
        loadMassKg = VehicleLoadPolicy.loadMassKg(telemetry.loadMassKg(), loadRate);
        loadRate = VehicleLoadPolicy.loadRateFromMass(loadMassKg);
        overloadStatus = VehicleLoadPolicy.overloadStatus(loadMassKg);
        availableTractionCount = VehicleLoadPolicy.normalizeUnitCount(
            telemetry.availableTractionCount(),
            VehicleLoadPolicy.NOMINAL_TRACTION_UNITS
        );
        availableBrakeCount = VehicleLoadPolicy.normalizeUnitCount(
            telemetry.availableBrakeCount(),
            VehicleLoadPolicy.NOMINAL_BRAKE_UNITS
        );
        vehicleProtectionReason = VehicleLoadPolicy.vehicleProtectionReason(overloadStatus);
        if (telemetry.emergencyBrakeApplied()) {
            brakeState = "EMERGENCY";
            status = "EMERGENCY_BRAKE";
            operationMode = "ATP_BRAKE";
            faultCode = "ATP_BRAKE";
            faultLevel = Math.max(faultLevel, 3);
            availableOperationMode = "NO_DEPARTURE";
        }
        vehicleFaultSpeedLimitMetersPerSecond = telemetry.faultSpeedLimitMetersPerSecond();
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

    public String id() {
        return id;
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

    private String mergeVehicleProtectionReason(String currentReason, String loadReason) {
        if (loadReason != null && !"NONE".equals(loadReason)) {
            return loadReason;
        }
        return currentReason == null || currentReason.isBlank() ? "NONE" : currentReason;
    }

    private String resolveAvailableOperationMode(String currentMode, String protectionReason) {
        if (("OVERLOAD".equals(protectionReason) || "CRITICAL_OVERLOAD".equals(protectionReason))
            && "NORMAL".equals(currentMode)) {
            return "DEGRADED";
        }
        return currentMode;
    }
}
