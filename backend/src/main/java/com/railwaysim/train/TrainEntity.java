package com.railwaysim.train;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehicleLoadPolicy;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
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
        String effectiveFaultCode = getFaultCode();
        return new TrainState(
            id,
            routeId,
            id,
            controlSession == null ? null : controlSession.state().name(),
            controlSession == null ? null : controlSession.signalNetworkStatus(),
            controlSession == null ? null : controlSession.powerNetworkStatus(),
            controlSession == null ? null : controlSession.reason(),
            controlSession == null ? 0 : controlSession.linkId(),
            controlSession == null ? "DOWN" : controlSession.direction().name(),
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
            injectedFaultCode == null ? status : "FAULT",
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

    public String id() { return id; }

    /**
     * Store the latest driver-cab console state received via the 8080 PLC gateway (browser console)
     * or the machine-room PLC TCP link. This is the display mirror the front-end cab reads back; it does
     * not itself drive vehicle physics (9300 remains authoritative).
     */
    public void applyDriverCabState(DriverCabStateSnapshot snapshot) {
        if (snapshot != null) {
            this.driverCabState = snapshot;
        }
    }

    /** @deprecated 9300 trainStates snapshots are the sole vehicle-state authority. */
    @Deprecated(forRemoval = true)
    public void applyOperationalTelemetry(TrainOperationalTelemetry telemetry) {
        throw new UnsupportedOperationException(
            "central vehicle telemetry writes are retired; use 9300 trainStates via applyExternalSnapshot"
        );
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

    public void injectFault(String faultCode) {
        injectedFaultCode = faultCode == null || faultCode.isBlank() ? "TRAIN_FAULT" : faultCode;
    }

    public String injectedFaultCode() {
        return injectedFaultCode;
    }

    public void clearFault() {
        injectedFaultCode = null;
    }

    public String getFaultCode() {
        return injectedFaultCode != null ? injectedFaultCode : faultCode;
    }

    private String resolveStatus(TrainStateReport report, VehiclePhysicsOutput output) {
        if (output == null || report == null) {
            return status;
        }
        if (!"OK".equals(report.faultCode()) || report.faultLevel() >= 3) {
            return "FAULT";
        }
        if (report.faultLevel() > 0 || report.operationMode() == null) {
            return "DEGRADED";
        }
        if ("STATION_STOPPED".equals(dynamicsState) && speedMetersPerSecond <= 0.2) {
            return "DWELLING";
        }
        return "RUNNING";
    }

    private void updateStationTracking(TrainStateReport report, double deltaSeconds) {
        boolean dwelling = "STATION_STOPPED".equals(report.dynamicsState()) && this.speedMetersPerSecond <= 0.2;
        if (dwelling) {
            if (currentStationId == null) {
                currentStationId = inferStationId();
            }
            dwellElapsedAccumulatorSeconds += deltaSeconds;
            dwellElapsedSeconds = (int) Math.floor(dwellElapsedAccumulatorSeconds);
        } else {
            if (currentStationId != null || dwellElapsedSeconds > 0) {
                lastDepartureAt = Instant.now().toString();
                currentStationId = null;
                dwellElapsedAccumulatorSeconds = 0;
                dwellElapsedSeconds = 0;
            }
        }
    }

    private String inferStationId() {
        if (stations.isEmpty()) return null;
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

    private String mergeVehicleProtectionReason(String trainReason, String overloadReason) {
        if (overloadReason != null && !"NONE".equals(overloadReason)) return overloadReason;
        return (trainReason == null || trainReason.isBlank()) ? "NONE" : trainReason;
    }

    private String resolveAvailableOperationMode(String currentMode, String protectionReason) {
        if (injectedFaultCode != null) return "NO_DEPARTURE";
        boolean overloaded = "OVERLOAD".equals(overloadStatus) || "CRUSH_OVERLOAD".equals(overloadStatus);
        if (overloaded && "NORMAL".equals(currentMode)) return "DEGRADED";
        return currentMode;
    }
}
