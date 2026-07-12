package com.railwaysim.train;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehicleLoadPolicy;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.drivercab.DriverCabMasterHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicle.drivercab.DriverCabStateSnapshot;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import java.time.Instant;
import java.util.List;

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
    private String currentStationId;
    private double dwellElapsedAccumulatorSeconds;
    private int dwellElapsedSeconds;
    private String lastDepartureAt;
    private DriverCabStateSnapshot driverCabState;
    private final List<OperationalLineData.StationDefinition> stations;

    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters) {
        this(id, routeId, positionMeters, lengthMeters, 0.35);
    }

    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters, double loadRate) {
        this(id, routeId, positionMeters, lengthMeters, loadRate, null);
    }

    public TrainEntity(
        String id,
        String routeId,
        double positionMeters,
        double lengthMeters,
        double loadRate,
        OperationalLineData lineData
    ) {
        this.id = id;
        this.routeId = routeId;
        this.positionMeters = positionMeters;
        this.lengthMeters = lengthMeters;
        this.loadRate = loadRate;
        this.loadMassKg = VehicleLoadPolicy.loadMassFromRate(loadRate);
        this.stations = lineData == null ? List.of() : lineData.stations();
    }

    public void applyPhysicsOutput(VehiclePhysicsOutput output, TrainStateReport report, double deltaSeconds) {
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
        updateStationTracking(report, deltaSeconds);
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
            effectiveFaultCode,
            currentStationId,
            dwellElapsedSeconds,
            lastDepartureAt,
            driverCabState
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

    /**
     * 从 9300 的权威状态快照批量更新镜像状态（EXTERNAL_HTTP 模式）。
     * 9300 是权威，本方法做全量覆写以避免状态漂移。
     */
    public void applyExternalSnapshot(TrainState snapshot) {
        this.positionMeters = snapshot.positionMeters();
        this.speedMetersPerSecond = snapshot.speedMetersPerSecond();
        this.accelerationMetersPerSecondSquared = snapshot.accelerationMetersPerSecondSquared();
        this.tractionForceNewtons = snapshot.tractionForceNewtons();
        this.brakeForceNewtons = snapshot.brakeForceNewtons();
        this.regenBrakeForceNewtons = snapshot.regenBrakeForceNewtons();
        this.railCurrentAmps = snapshot.railCurrentAmps();
        this.tractionPowerWatts = snapshot.tractionPowerWatts();
        this.regenPowerWatts = snapshot.regenPowerWatts();
        this.energyConsumedKwh = snapshot.energyConsumedKwh();
        this.energyRegeneratedKwh = snapshot.energyRegeneratedKwh();
        this.faultCode = snapshot.faultCode();
        this.operationMode = snapshot.operationMode();
        this.doorState = snapshot.doorState();
        this.tractionState = snapshot.tractionState();
        this.brakeState = snapshot.brakeState();
        this.currentCollectionStatus = snapshot.currentCollectionStatus();
        this.tractionAvailable = snapshot.tractionAvailable();
        this.brakeAvailable = snapshot.brakeAvailable();
        this.selfCheckStatus = snapshot.selfCheckStatus();
        this.faultLevel = snapshot.faultLevel();
        this.availableOperationMode = snapshot.availableOperationMode();
        this.dataQuality = snapshot.dataQuality();
        this.loadMassKg = snapshot.loadMassKg();
        this.loadRate = snapshot.loadRate();
        this.overloadStatus = snapshot.overloadStatus();
        this.availableTractionCount = snapshot.availableTractionCount();
        this.availableBrakeCount = snapshot.availableBrakeCount();
        this.vehicleProtectionReason = snapshot.vehicleProtectionReason();
        this.dynamicsState = snapshot.dynamicsState();
        this.dynamicsConstraintReason = snapshot.dynamicsConstraintReason();
        this.speedLimitMetersPerSecond = snapshot.speedLimitMetersPerSecond();
        this.movementAuthorityDistanceMeters = snapshot.movementAuthorityDistanceMeters();
        this.stationDistanceMeters = snapshot.stationDistanceMeters();
        this.stoppingDistanceMeters = snapshot.stoppingDistanceMeters();
        this.currentStationId = snapshot.currentStationId();
        this.dwellElapsedSeconds = snapshot.dwellElapsedSeconds();
        this.lastDepartureAt = snapshot.lastDepartureAt();
        this.status = resolveStatusFromSnapshot(snapshot);
        if (snapshot.driverCabState() != null) {
            this.driverCabState = snapshot.driverCabState();
        }
    }

    private String resolveStatusFromSnapshot(TrainState snapshot) {
        if (snapshot.faultLevel() >= 3) return "FAULT";
        if (snapshot.faultLevel() > 0) return "DEGRADED";
        if ("STATION_STOPPED".equals(snapshot.dynamicsState()) && snapshot.speedMetersPerSecond() <= 0.2) return "DWELLING";
        return "RUNNING";
    }

    public void applyDriverCabInput(DriverCabPlcInputPacket input) {
        if (input == null) {
            throw new IllegalArgumentException("driver cab PLC input is required");
        }
        driverCabState = DriverCabStateSnapshot.fromInput(input, Instant.now());
        if (input.openDoorRequested()) {
            doorState = "OPEN";
        } else if (input.closeDoorRequested()) {
            doorState = "CLOSED_LOCKED";
        }

        if (!input.keySwitchLocked()) {
            operationMode = "STANDBY";
            tractionState = "IDLE";
            brakeState = speedMetersPerSecond > 0.1 ? "SERVICE" : "RELEASED";
            tractionAvailable = false;
            selfCheckStatus = "FAIL";
            faultLevel = Math.max(faultLevel, 2);
            availableOperationMode = "NO_DEPARTURE";
            vehicleProtectionReason = "DRIVER_CAB_KEY_OFF";
            faultCode = "DRIVER_CAB_KEY_OFF";
            return;
        }

        if ("DRIVER_CAB_KEY_OFF".equals(faultCode)) {
            faultCode = "OK";
            faultLevel = 0;
            selfCheckStatus = "PASS";
            availableOperationMode = "NORMAL";
            tractionAvailable = true;
            vehicleProtectionReason = "NONE";
        }

        if (input.emergencyBrakeRequested()) {
            operationMode = "ATP_BRAKE";
            brakeState = "EMERGENCY";
            tractionState = "IDLE";
            status = "EMERGENCY_BRAKE";
            faultCode = "DRIVER_CAB_EMERGENCY_BRAKE";
            faultLevel = Math.max(faultLevel, 3);
            availableOperationMode = "NO_DEPARTURE";
            vehicleProtectionReason = "DRIVER_CAB_EMERGENCY_BRAKE";
            return;
        }

        if (input.automaticTurnbackFlag()) {
            operationMode = "AR";
        } else if (input.atoStartFlag()) {
            operationMode = "ATO";
        } else if (input.masterHandleState() != DriverCabMasterHandleState.ZERO) {
            operationMode = "DEGRADED";
        }

        switch (input.masterHandleState()) {
            case TRACTION -> {
                tractionState = "APPLYING";
                brakeState = "RELEASED";
            }
            case BRAKE -> {
                tractionState = "IDLE";
                brakeState = "SERVICE";
            }
            default -> {
                if (!"EMERGENCY".equals(brakeState)) {
                    tractionState = "IDLE";
                    brakeState = "RELEASED";
                }
            }
        }
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

    private void updateStationTracking(TrainStateReport report, double deltaSeconds) {
        boolean dwelling = "STATION_STOPPED".equals(report.dynamicsState()) && speedMetersPerSecond <= 0.2;
        if (dwelling) {
            if (currentStationId == null) {
                currentStationId = inferStationId();
            }
            dwellElapsedAccumulatorSeconds += Math.max(0, deltaSeconds);
            dwellElapsedSeconds = (int) Math.floor(dwellElapsedAccumulatorSeconds);
            return;
        }

        if (currentStationId != null || dwellElapsedSeconds > 0) {
            lastDepartureAt = Instant.now().toString();
            currentStationId = null;
            dwellElapsedAccumulatorSeconds = 0;
            dwellElapsedSeconds = 0;
        }
    }

    private String inferStationId() {
        if (!stations.isEmpty()) {
            String nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (OperationalLineData.StationDefinition station : stations) {
                double distance = Math.abs(positionMeters - station.centerMeters());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = station.id();
                }
            }
            return nearestDistance <= 30 ? nearest : null;
        }
        double[] stationPositions = {0, 1250, 2500, 3750, 5000};
        String[] stationIds = {"S01", "S02", "S03", "S04", "S05"};
        String nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < stationPositions.length; i++) {
            double distance = Math.abs(positionMeters - stationPositions[i]);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = stationIds[i];
            }
        }
        return nearestDistance <= 30 ? nearest : null;
    }

    public String id() {
        return id;
    }

    private String resolveStatus(TrainStateReport report, VehiclePhysicsOutput output) {
        if (report.emergencyBrakeCommand()) {
            return "EMERGENCY_BRAKE";
        }
        if ("STATION_STOPPED".equals(report.dynamicsState()) && speedMetersPerSecond <= 0.2) {
            return "DWELLING";
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
