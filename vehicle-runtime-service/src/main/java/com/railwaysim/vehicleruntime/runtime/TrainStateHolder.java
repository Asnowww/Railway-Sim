package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.drivercab.DriverCabMasterHandleState;
import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import java.util.ArrayList;
import java.util.List;

/**
 * 9300 每列车权威状态持有者。
 * <p>等效中央后端 TrainEntity 的可变字段集，但脱离 ExternalTrainControlSession 依赖。
 * 状态由 applyPhysicsOutput() / applyControlReport() / applyDriverCabInput() 驱动更新，
 * snapshot() 生成不可变 TrainStateSnapshot 供响应返回中央。
 */
public class TrainStateHolder {

    // ========== 标识字段（不可变） ==========
    private final String trainId;
    private final String routeId;
    private final double lengthMeters;
    private final VehicleLoadPolicy loadPolicy;
    private final VehicleParameters vehicleParameters;
    private final List<StationDef> stations;

    // ========== 物理状态（每 tick 由物理引擎更新） ==========
    private double positionMeters;
    private double speedMetersPerSecond;
    private double accelerationMetersPerSecondSquared;
    private double tractionForceNewtons;
    private double brakeForceNewtons;
    private double regenBrakeForceNewtons;
    private double railCurrentAmps;
    private double tractionPowerWatts;
    private double regenPowerWatts;
    private double energyConsumedKwh;
    private double energyRegeneratedKwh;

    // ========== TCMS / 运营状态 ==========
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
    private boolean driverCabTractionCut;
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
    private String faultCode = "OK";

    // ========== 故障注入 ==========
    private String injectedFaultCode;

    // ========== 车站追踪 ==========
    private String currentStationId;
    private double dwellElapsedAccumulatorSeconds;
    private int dwellElapsedSeconds;
    private String lastDepartureAt;

    // ========== 司控台状态 ==========
    private boolean driverCabKeyOn;
    private boolean driverCabEmergencyBrake;

    // ========== 约束快照（每 tick 由 prepare() 更新，供 snapshot() 使用） ==========
    private int linkId;
    private String direction = "DOWN";

    // ========== 构造 ==========

    public TrainStateHolder(
        String trainId,
        VehicleParameters vehicleParameters,
        VehicleLoadPolicy loadPolicy,
        List<StationDef> stationDefinitions
    ) {
        this.trainId = trainId;
        this.routeId = "ROUTE_DEFAULT";
        this.lengthMeters = vehicleParameters.lengthMeters();
        this.vehicleParameters = vehicleParameters;
        this.loadPolicy = loadPolicy;
        this.stations = stationDefinitions == null ? List.of() : List.copyOf(stationDefinitions);
    }

    // ========== 初始化 ==========

    /** 从注册快照初始化状态（仅首次调用）。 */
    public void initialize(TrainStateSnapshot snapshot) {
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
        this.loadRate = snapshot.loadRate();
        this.loadMassKg = snapshot.loadMassKg();
        this.overloadStatus = snapshot.overloadStatus();
        this.availableTractionCount = snapshot.availableTractionCount();
        this.availableBrakeCount = snapshot.availableBrakeCount();
        this.vehicleProtectionReason = snapshot.vehicleProtectionReason();
        this.status = snapshot.status();
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
        this.dynamicsState = snapshot.dynamicsState();
        this.dynamicsConstraintReason = snapshot.dynamicsConstraintReason();
        this.speedLimitMetersPerSecond = snapshot.speedLimitMetersPerSecond();
        this.movementAuthorityDistanceMeters = snapshot.movementAuthorityDistanceMeters();
        this.stationDistanceMeters = snapshot.stationDistanceMeters();
        this.stoppingDistanceMeters = snapshot.stoppingDistanceMeters();
        this.faultCode = snapshot.faultCode();
        this.linkId = snapshot.linkId();
        this.direction = snapshot.direction();
        if (snapshot.currentStationId() != null) {
            this.currentStationId = snapshot.currentStationId();
            this.dwellElapsedSeconds = snapshot.dwellElapsedSeconds();
            this.lastDepartureAt = snapshot.lastDepartureAt();
        }
    }

    /** 在新运行时开始时重置 tick 相关状态。 */
    public void resetForNewRun() {
        dwellElapsedAccumulatorSeconds = 0;
        dwellElapsedSeconds = 0;
        currentStationId = null;
        lastDepartureAt = null;
    }

    // ========== 约束更新（每 tick prepare 时调用） ==========

    public void applyConstraints(int tickLinkId, String tickDirection) {
        this.linkId = tickLinkId;
        this.direction = tickDirection;
    }

    // ========== 物理输出应用 ==========

    /** 应用物理引擎输出，更新位置/速度/力/能耗。 */
    public void applyPhysicsOutput(VehiclePhysicsOutputDto output) {
        this.positionMeters = Math.max(0, output.newPositionMeters());
        this.speedMetersPerSecond = Math.max(0, output.newSpeedMetersPerSecond());
        this.accelerationMetersPerSecondSquared = output.accelerationMetersPerSecondSquared();
        this.tractionForceNewtons = output.tractionForceNewtons();
        this.brakeForceNewtons = output.brakeForceNewtons();
        this.regenBrakeForceNewtons = output.regenBrakeForceNewtons();
        this.railCurrentAmps = output.railCurrentAmps();
        this.tractionPowerWatts = output.tractionPowerWatts();
        this.regenPowerWatts = output.regenPowerWatts();
        this.energyConsumedKwh = output.energyConsumedKwh();
        this.energyRegeneratedKwh = output.energyRegeneratedKwh();
        this.faultCode = output.faultCode() != null ? output.faultCode() : "OK";
        this.dataQuality = "OK".equals(this.faultCode) ? "GOOD" : "DEGRADED";
    }

    // ========== 控制报告应用 ==========

    /** 应用 TCMS 控制报告，更新运营/诊断/安全状态。 */
    public void applyControlReport(
        String opMode, boolean doorClosed, String doorState,
        String tracState, String brkState, String ccStatus,
        boolean tracAvail, boolean brkAvail, String selfCheck,
        int fLevel, String availOpMode, String dq,
        double lMassKg, String oStatus, int availTracCount, int availBrkCount,
        String vProtReason, String dynState, String dynConstraintReason,
        double spdLimit, double maDist, double stnDist, double stopDist,
        String fCode
    ) {
        this.operationMode = opMode;
        this.doorState = doorState;
        this.tractionState = tracState;
        this.brakeState = brkState;
        this.currentCollectionStatus = ccStatus;
        this.tractionAvailable = tracAvail;
        this.brakeAvailable = brkAvail;
        this.selfCheckStatus = selfCheck;
        this.faultLevel = fLevel;
        this.availableOperationMode = availOpMode;
        this.dataQuality = dq;
        this.loadMassKg = lMassKg;
        this.loadRate = VehicleLoadPolicy.loadRateFromMass(lMassKg);
        this.overloadStatus = oStatus;
        this.availableTractionCount = availTracCount;
        this.availableBrakeCount = availBrkCount;
        this.vehicleProtectionReason = vProtReason;
        this.dynamicsState = dynState;
        this.dynamicsConstraintReason = dynConstraintReason;
        this.speedLimitMetersPerSecond = spdLimit;
        this.movementAuthorityDistanceMeters = maDist;
        this.stationDistanceMeters = stnDist;
        this.stoppingDistanceMeters = stopDist;
        if (fCode != null) {
            this.faultCode = fCode;
        }
        this.status = resolveStatus();
        updateStationTracking(dynState);
    }

    // ========== 司控台输入应用（从中央 TrainEntity.applyDriverCabInput() 移植） ==========

    /**
     * 应用司控台 PLC 输入，变更列车运营状态。
     * 移植自中央后端 TrainEntity.applyDriverCabInput()。
     */
    public void applyDriverCabInput(DriverCabPlcInputPacket input) {
        if (input == null) {
            throw new IllegalArgumentException("driver cab PLC input is required");
        }

        if (input.openDoorRequested()) {
            doorState = "OPEN";
        } else if (input.closeDoorRequested()) {
            doorState = "CLOSED_LOCKED";
        }

        // 钥匙开关
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
            driverCabKeyOn = false;
            return;
        }

        // 清除钥匙关闭故障
        if ("DRIVER_CAB_KEY_OFF".equals(faultCode)) {
            faultCode = "OK";
            faultLevel = 0;
            selfCheckStatus = "PASS";
            availableOperationMode = "NORMAL";
            tractionAvailable = true;
            vehicleProtectionReason = "NONE";
        }
        driverCabKeyOn = true;

        // 紧急制动
        if (input.emergencyBrakeRequested()) {
            operationMode = "ATP_BRAKE";
            brakeState = "EMERGENCY";
            tractionState = "IDLE";
            status = "EMERGENCY_BRAKE";
            faultCode = "DRIVER_CAB_EMERGENCY_BRAKE";
            faultLevel = Math.max(faultLevel, 3);
            availableOperationMode = "NO_DEPARTURE";
            vehicleProtectionReason = "DRIVER_CAB_EMERGENCY_BRAKE";
            driverCabEmergencyBrake = true;
            return;
        }
        driverCabEmergencyBrake = false;

        // 操作模式
        if (input.automaticTurnbackFlag()) {
            operationMode = "AR";
        } else if (input.atoStartFlag()) {
            operationMode = "ATO";
        } else if (input.masterHandleState() != DriverCabMasterHandleState.ZERO) {
            operationMode = "DEGRADED";
        }

        // 主手柄
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

    public void applyTractionCut(boolean requested) {
        driverCabTractionCut = requested;
        if (requested) {
            tractionState = "IDLE";
            vehicleProtectionReason = "DRIVER_CAB_TRACTION_CUT";
            faultCode = "DRIVER_CAB_TRACTION_CUT";
            faultLevel = Math.max(faultLevel, 2);
        } else if ("DRIVER_CAB_TRACTION_CUT".equals(faultCode)) {
            vehicleProtectionReason = "NONE";
            faultCode = "OK";
            faultLevel = 0;
        }
    }

    // ========== 故障注入 ==========

    public void injectFault(String faultCode) {
        this.injectedFaultCode = (faultCode == null || faultCode.isBlank()) ? "TRAIN_FAULT" : faultCode;
    }

    public void clearFault() {
        this.injectedFaultCode = null;
    }

    public String injectedFaultCode() {
        return injectedFaultCode;
    }

    public String getFaultCode() {
        return injectedFaultCode != null ? injectedFaultCode : faultCode;
    }

    // ========== 快照 ==========

    /** 生成不可变 TrainStateSnapshot。 */
    public TrainStateSnapshot snapshot() {
        String effStatus = resolveEffectiveStatus();
        String effDataQuality = injectedFaultCode == null ? dataQuality : "INVALID";
        String effSelfCheck = injectedFaultCode == null ? selfCheckStatus : "FAIL";
        int effFaultLevel = injectedFaultCode == null ? faultLevel : 3;
        String effFaultCode = getFaultCode();
        String effAvailOpMode = resolveEffectiveAvailableOperationMode();
        String effVProtReason = resolveEffectiveVehicleProtectionReason();
        boolean effZeroSpeed = speedMetersPerSecond <= 0.05;

        return new TrainStateSnapshot(
            trainId, routeId, trainId,
            "IN_SERVICE", "ATTACHED", "ATTACHED",
            "AUTHORITY_9300", linkId, direction,
            positionMeters, speedMetersPerSecond, lengthMeters,
            positionMeters, Math.max(0, positionMeters - lengthMeters),
            loadRate, loadMassKg, overloadStatus,
            effectiveAvailableTractionCount(), effectiveAvailableBrakeCount(),
            effVProtReason, effStatus, operationMode, effZeroSpeed,
            effectiveDoorState(), effectiveTractionState(), effectiveBrakeState(),
            effectiveCurrentCollectionStatus(),
            effectiveTractionAvailable(), effectiveBrakeAvailable(),
            effSelfCheck, effFaultLevel, effAvailOpMode, effDataQuality,
            dynamicsState, dynamicsConstraintReason,
            speedLimitMetersPerSecond, 0.0,
            movementAuthorityDistanceMeters, stationDistanceMeters, stoppingDistanceMeters,
            accelerationMetersPerSecondSquared,
            tractionForceNewtons, brakeForceNewtons, regenBrakeForceNewtons,
            railCurrentAmps, tractionPowerWatts, regenPowerWatts,
            energyConsumedKwh, energyRegeneratedKwh, effFaultCode,
            currentStationId, dwellElapsedSeconds, lastDepartureAt
        );
    }

    // ========== Getters（供 VehicleControlQueue 等调用方使用） ==========

    public String trainId() { return trainId; }
    public double getPositionMeters() { return positionMeters; }
    public double getSpeedMetersPerSecond() { return speedMetersPerSecond; }
    public double getLoadRate() { return loadRate; }
    public double getLoadMassKg() { return loadMassKg; }
    public String getDoorState() { return effectiveDoorState(); }
    public String getTractionState() { return effectiveTractionState(); }
    public String getBrakeState() { return effectiveBrakeState(); }
    public String getCurrentCollectionStatus() { return effectiveCurrentCollectionStatus(); }
    public boolean isTractionAvailable() { return effectiveTractionAvailable(); }
    public boolean isBrakeAvailable() { return effectiveBrakeAvailable(); }
    public int getAvailableTractionCount() { return effectiveAvailableTractionCount(); }
    public int getAvailableBrakeCount() { return effectiveAvailableBrakeCount(); }
    public String getSelfCheckStatus() { return injectedFaultCode == null ? selfCheckStatus : "FAIL"; }
    public String getControlSessionState() { return "IN_SERVICE"; }
    public String getSignalNetworkStatus() { return "ATTACHED"; }
    public String getPowerNetworkStatus() { return "ATTACHED"; }
    public int getLinkId() { return linkId; }
    public String getDirection() { return direction; }
    public double getEnergyConsumedKwh() { return energyConsumedKwh; }
    public double getEnergyRegeneratedKwh() { return energyRegeneratedKwh; }
    public double getOverloadMassKg() { return loadMassKg; }
    public int getFaultLevel() { return faultLevel; }
    public String getOperationMode() { return operationMode; }
    public String getDynamicsState() { return dynamicsState; }
    public String getVehicleProtectionReason() { return resolveEffectiveVehicleProtectionReason(); }
    public double getSpeedLimitMetersPerSecond() { return speedLimitMetersPerSecond; }
    public double getMovementAuthorityDistanceMeters() { return movementAuthorityDistanceMeters; }
    public double getStationDistanceMeters() { return stationDistanceMeters; }
    public double getStoppingDistanceMeters() { return stoppingDistanceMeters; }
    public String getDynamicsConstraintReason() { return dynamicsConstraintReason; }

    // ========== 内部辅助方法（移植自 TrainEntity） ==========

    private String resolveStatus() {
        if (driverCabEmergencyBrake) return "EMERGENCY_BRAKE";
        if ("EMERGENCY".equals(brakeState)) return "EMERGENCY_BRAKE";
        if ("STATION_STOPPED".equals(dynamicsState) && speedMetersPerSecond <= 0.2) return "DWELLING";
        if (injectedFaultCode != null) return "FAULT";
        if (faultLevel >= 3) return "FAULT";
        if (faultLevel > 0) return "DEGRADED";
        return "RUNNING";
    }

    private String resolveEffectiveStatus() {
        if (injectedFaultCode != null) return "FAULT";
        return status;
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
        return tractionAvailable && !driverCabTractionCut
            && !"TRACTION_UNAVAILABLE".equals(injectedFaultCode);
    }

    private boolean effectiveBrakeAvailable() {
        return brakeAvailable && !"BRAKE_UNAVAILABLE".equals(injectedFaultCode);
    }

    private int effectiveAvailableTractionCount() {
        return effectiveTractionAvailable() ? availableTractionCount : 0;
    }

    private int effectiveAvailableBrakeCount() {
        return effectiveBrakeAvailable() ? availableBrakeCount : 0;
    }

    private String resolveEffectiveVehicleProtectionReason() {
        String loadReason = loadPolicy.vehicleProtectionReason(overloadStatus);
        if (loadReason != null && !"NONE".equals(loadReason)) return loadReason;
        return (vehicleProtectionReason == null || vehicleProtectionReason.isBlank()) ? "NONE" : vehicleProtectionReason;
    }

    private String resolveEffectiveAvailableOperationMode() {
        if (injectedFaultCode != null) return "NO_DEPARTURE";
        if (("OVERLOAD".equals(overloadStatus) || "CRUSH_OVERLOAD".equals(overloadStatus))
            && "NORMAL".equals(availableOperationMode)) {
            return "DEGRADED";
        }
        return availableOperationMode;
    }

    private void updateStationTracking(String dynState) {
        boolean dwelling = "STATION_STOPPED".equals(dynState) && speedMetersPerSecond <= 0.2;
        if (dwelling) {
            if (currentStationId == null) {
                currentStationId = inferStationId();
            }
            dwellElapsedAccumulatorSeconds += 0.1;
            dwellElapsedSeconds = (int) Math.floor(dwellElapsedAccumulatorSeconds);
            return;
        }
        if (currentStationId != null || dwellElapsedSeconds > 0) {
            lastDepartureAt = java.time.Instant.now().toString();
            currentStationId = null;
            dwellElapsedAccumulatorSeconds = 0;
            dwellElapsedSeconds = 0;
        }
    }

    private String inferStationId() {
        if (stations.isEmpty()) return null;
        String nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (StationDef station : stations) {
            double distance = Math.abs(positionMeters - station.centerMeters);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = station.id;
            }
        }
        return nearestDistance <= 30 ? nearest : null;
    }

    /** 由线路权威配置下发的只读车站目标；9300 不创建或修改基础设施。 */
    public record StationDef(
        String id, String name, double centerMeters, List<String> platformIds
    ) {
        public StationDef {
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
        }
    }
}
