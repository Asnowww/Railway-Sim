package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.StoppingControlProperties;
import com.railwaysim.vehicleruntime.model.DispatchConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrainStateReportDto;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeInstanceState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单车运行时实例持有一对控制/仿真队列，是外部服务的最小隔离单元。
 * 持有 TrainStateHolder 作为该车权威状态。
 */
final class VehicleRuntimeInstance {

    private final String trainId;
    private final VehicleControlQueue controlQueue;
    private final VehicleLoadPolicy loadPolicy;
    private final VehicleParameters vehicleParameters;
    private final TrainStateHolder trainState; // NEW: authoritative train state
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile long lastTick = -1;
    private volatile String lifecycleState = "READY";
    private volatile String controlQueueStatus = "IDLE";
    private volatile String simulationQueueStatus = "IDLE";
    private volatile long latencyMillis;
    private volatile String dataQuality = "GOOD";
    private volatile String reason = "READY";
    private volatile Instant updatedAt = Instant.now();

    VehicleRuntimeInstance(
        String trainId,
        VehicleRuntimeProperties properties,
        VehicleParameters vehicleParameters,
        DriverCommandHolder driverCommandHolder,
        StoppingControlProperties stoppingProperties,
        List<TrainStateHolder.StationDef> stationDefinitions
    ) {
        this.trainId = trainId;
        this.vehicleParameters = vehicleParameters;
        this.loadPolicy = new VehicleLoadPolicy(vehicleParameters);
        this.controlQueue = new VehicleControlQueue(
            properties, loadPolicy, vehicleParameters, driverCommandHolder, stoppingProperties);
        this.trainState = new TrainStateHolder(
            trainId, vehicleParameters, loadPolicy, stationDefinitions);
    }

    /** 从注册快照初始化 TrainStateHolder。 */
    void initializeState(TrainStateSnapshot snapshot) {
        trainState.initialize(snapshot);
    }

    /** 返回当前权威列车状态快照。 */
    TrainStateSnapshot snapshotTrainState() {
        return trainState.snapshot();
    }

    void applyDriverCabInput(com.railwaysim.vehicleruntime.drivercab.DriverCabPlcInputPacket input) {
        trainState.applyDriverCabInput(input);
    }

    void applyTractionCut(boolean requested) {
        trainState.applyTractionCut(requested);
    }

    void launch() {
        // 车辆仿真实例启动时同步唤醒本车控制队列，后续 tick 才能直接执行控制决策。
        lifecycleState = "CONTROL_AWAKE";
        controlQueueStatus = "READY";
        simulationQueueStatus = "READY";
        dataQuality = "GOOD";
        reason = "CONTROL_INSTANCE_AWAKE";
        updatedAt = Instant.now();
    }

    void rolloverRun() {
        inFlight.set(false);
        lastTick = -1;
        lifecycleState = "CONTROL_AWAKE";
        controlQueueStatus = "READY";
        simulationQueueStatus = "READY";
        latencyMillis = 0;
        dataQuality = "GOOD";
        reason = "RUN_ROLLOVER";
        updatedAt = Instant.now();
        trainState.resetForNewRun();
    }

    /**
     * 准备阶段：从本地 TrainStateHolder 读取状态，执行控制决策。
     * 不再接收 TrainStateSnapshot 参数——状态由 TrainStateHolder 自身持有。
     */
    PreparedStep prepare(
        long tick, double deltaSeconds,
        MovementAuthoritySnapshot authority,
        TrackConstraintSnapshot track,
        DispatchConstraintSnapshot dispatch,
        PowerConstraintSnapshot power
    ) {
        Instant startedAt = Instant.now();
        if (!inFlight.compareAndSet(false, true)) {
            rejectWithoutFault("INSTANCE_BUSY", startedAt);
            return null;
        }
        try {
            if (tick <= lastTick) {
                rejectWithoutFault("STALE_OR_DUPLICATE_TICK", startedAt);
                inFlight.set(false);
                return null;
            }
            controlQueueStatus = "RUNNING";
            // 从本地 TrainStateHolder 读取状态
            VehiclePhysicsInputDto input = controlQueue.control(
                tick, deltaSeconds, trainState, authority, track, dispatch, power
            );
            controlQueueStatus = "DONE";
            simulationQueueStatus = "PREPARED";
            return new PreparedStep(tick, input, startedAt);
        } catch (VehicleRuntimeQueue.QueueRejectedException exception) {
            reject(exception.getMessage(), startedAt);
            inFlight.set(false);
            return null;
        } catch (RuntimeException exception) {
            reject(exception.getClass().getSimpleName(), startedAt);
            inFlight.set(false);
            return null;
        }
    }

    StepResult apply(PreparedStep prepared, VehiclePhysicsOutputDto output, String stepReason) {
        try {
            simulationQueueStatus = "DONE";
            // 更新本地 TrainStateHolder
            trainState.applyPhysicsOutput(output);
            TrainStateReportDto report = buildReport(prepared.input(), output);
            trainState.applyControlReport(
                report.operationMode(), report.doorClosed(), report.doorState(),
                report.tractionState(), report.brakeState(), report.currentCollectionStatus(),
                report.tractionAvailable(), report.brakeAvailable(),
                report.selfCheckStatus(), report.faultLevel(), report.availableOperationMode(), report.dataQuality(),
                report.loadMassKg(), report.overloadStatus(),
                report.availableTractionCount(), report.availableBrakeCount(),
                report.vehicleProtectionReason(),
                report.dynamicsState(), report.dynamicsConstraintReason(),
                report.speedLimitMetersPerSecond(), report.movementAuthorityDistanceMeters(),
                report.stationDistanceMeters(), report.stoppingDistanceMeters(),
                report.faultCode()
            );
            TrainStateSnapshot newState = trainState.snapshot();
            lastTick = prepared.tick();
            lifecycleState = "RUNNING";
            dataQuality = "GOOD".equals(output.dataQuality()) && "OK".equals(stepReason) ? "GOOD" : "DEGRADED";
            reason = stepReason;
            latencyMillis = Duration.between(prepared.startedAt(), Instant.now()).toMillis();
            updatedAt = Instant.now();
            return new StepResult(output, report, newState, state());
        } finally {
            inFlight.set(false);
        }
    }

    void markSimulationRunning() {
        simulationQueueStatus = "RUNNING";
    }

    void abort(String abortReason, PreparedStep prepared) {
        reject(abortReason, prepared.startedAt());
        inFlight.set(false);
    }

    VehicleRuntimeInstanceState state() {
        return new VehicleRuntimeInstanceState(
            trainId,
            lifecycleState,
            controlQueueStatus,
            simulationQueueStatus,
            lastTick,
            latencyMillis,
            dataQuality,
            reason,
            updatedAt
        );
    }

    private void reject(String rejectReason, Instant startedAt) {
        lifecycleState = "FAULT";
        dataQuality = "INVALID";
        reason = rejectReason;
        controlQueueStatus = "REJECTED";
        simulationQueueStatus = "REJECTED";
        latencyMillis = Duration.between(startedAt, Instant.now()).toMillis();
        updatedAt = Instant.now();
    }

    private void rejectWithoutFault(String rejectReason, Instant startedAt) {
        reason = rejectReason;
        controlQueueStatus = "REJECTED";
        simulationQueueStatus = "REJECTED";
        latencyMillis = Duration.between(startedAt, Instant.now()).toMillis();
        updatedAt = Instant.now();
    }

    private TrainStateReportDto buildReport(
        VehiclePhysicsInputDto input,
        VehiclePhysicsOutputDto output
    ) {
        var driverCommand = controlQueue.latestDriverCommand(input.trainId());
        boolean driverSelected = input.dynamicsConstraintReason() != null
            && input.dynamicsConstraintReason().startsWith("DRIVER_");
        double loadMassKg = loadPolicy.loadMassKg(trainState.getLoadMassKg(), trainState.getLoadRate());
        String overloadStatus = loadPolicy.overloadStatus(loadMassKg);
        int availableTractionCount = loadPolicy.normalizeUnitCount(trainState.getAvailableTractionCount(), VehicleLoadPolicy.NOMINAL_TRACTION_UNITS);
        int availableBrakeCount = loadPolicy.normalizeUnitCount(trainState.getAvailableBrakeCount(), VehicleLoadPolicy.NOMINAL_BRAKE_UNITS);
        return new TrainStateReportDto(
            input.trainId(),
            resolveOperationMode(input),
            input.doorClosed(),
            input.doorClosed() ? "CLOSED_LOCKED" : "OPEN",
            resolveTractionState(input, output),
            resolveBrakeState(input, output),
            resolveCurrentCollectionStatus(input, output),
            input.doorClosed() && input.powerAvailableWatts() > 0 && input.railVoltage() > 0,
            !"BRAKE_UNAVAILABLE".equals(output.faultCode()),
            resolveSelfCheckStatus(input, output),
            resolveFaultLevel(input, output),
            resolveAvailableOperationMode(input, output),
            resolveDataQuality(output),
            loadMassKg,
            overloadStatus,
            availableTractionCount,
            availableBrakeCount,
            resolveVehicleProtectionReason(overloadStatus),
            input.dynamicsState(),
            input.dynamicsConstraintReason(),
            input.speedLimitMetersPerSecond(),
            input.movementAuthorityDistanceMeters(),
            input.stationDistanceMeters(),
            input.stoppingDistanceMeters(),
            input.tractionCommand(),
            input.brakeCommand(),
            input.emergencyBrakeCommand(),
            input.railVoltage(),
            input.powerAvailableWatts(),
            output.faultCode(),
            driverSelected ? "DRIVER" : "CONTROL_OR_SAFETY",
            driverCommand == null ? null : driverCommand.commandId(),
            driverCommand == null ? null : driverCommand.traceId()
        );
    }

    // ========== 状态解析方法（移植自 VehicleRuntimeInstance 旧版 buildReport） ==========

    private String resolveOperationMode(VehiclePhysicsInputDto input) {
        if (input.emergencyBrakeCommand()) return "ATP_BRAKE";
        if ("STATION_BRAKE".equals(input.dynamicsState()) || "STATION_STOPPED".equals(input.dynamicsState())) return "STATION_CONTROL";
        if ("POWER_DERATED".equals(input.dynamicsState()) || "OVERLOAD_DERATED".equals(input.dynamicsState())) return "DEGRADED";
        return "ATO";
    }

    private String resolveTractionState(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (input.tractionCommand() <= 0 || output.tractionForceNewtons() <= 0) return "IDLE";
        if ("POWER_DERATED".equals(input.dynamicsState()) || "OVERLOAD_DERATED".equals(input.dynamicsState())
            || input.powerAvailableWatts() * vehicleParameters.drivetrain().tractionTotalEfficiency()
                < vehicleParameters.maxCurveMechanicalTractionPowerWatts()) return "DERATED";
        return "APPLYING";
    }

    private String resolveBrakeState(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (input.emergencyBrakeCommand()) return "EMERGENCY";
        return output.regenBrakeForceNewtons() > 0 ? "REGENERATIVE" : output.brakeForceNewtons() > 0 ? "SERVICE" : "RELEASED";
    }

    private String resolveCurrentCollectionStatus(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if ("CURRENT_COLLECTION_LOST".equals(output.faultCode()) || input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) return "LOST";
        if ("LOW_VOLTAGE".equals(output.faultCode()) || input.railVoltage() < vehicleParameters.power().minVoltage()) return "LOW_VOLTAGE";
        return "NORMAL";
    }

    private String resolveSelfCheckStatus(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (!input.doorClosed() || "CURRENT_COLLECTION_LOST".equals(output.faultCode()) || input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) return "FAIL";
        return "OK".equals(output.faultCode()) ? "PASS" : "WARN";
    }

    private int resolveFaultLevel(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) return 3;
        if ("OVERLOAD_DERATED".equals(input.dynamicsState())) return 1;
        return switch (output.faultCode()) {
            case "OK" -> input.emergencyBrakeCommand() ? 3 : 0;
            case "LOW_VOLTAGE", "TRACTION_UNAVAILABLE", "FMU_STEP_FAILED", "EXTERNAL_SIM_FALLBACK" -> 2;
            case "CURRENT_COLLECTION_LOST", "BRAKE_UNAVAILABLE", "DOOR_NOT_LOCKED", "ATP_BRAKE" -> 3;
            default -> 2;
        };
    }

    private String resolveAvailableOperationMode(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        int fl = resolveFaultLevel(input, output);
        return fl >= 3 ? "NO_DEPARTURE" : fl > 0 ? "DEGRADED" : "NORMAL";
    }

    private String resolveDataQuality(VehiclePhysicsOutputDto output) {
        return "OK".equals(output.faultCode()) ? "GOOD" : "INVALID";
    }

    private String resolveVehicleProtectionReason(String overloadStatus) {
        String loadReason = loadPolicy.vehicleProtectionReason(overloadStatus);
        return !"NONE".equals(loadReason) ? loadReason : trainState.getVehicleProtectionReason();
    }

    // ========== 内部记录 ==========

    record PreparedStep(long tick, VehiclePhysicsInputDto input, Instant startedAt) {
    }

    record StepResult(VehiclePhysicsOutputDto output, TrainStateReportDto report,
                      TrainStateSnapshot trainState, VehicleRuntimeInstanceState state) {
    }
}
