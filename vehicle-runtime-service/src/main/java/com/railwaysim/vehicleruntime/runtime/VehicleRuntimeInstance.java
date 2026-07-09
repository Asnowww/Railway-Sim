package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单车运行时实例持有一对控制/仿真队列，是外部服务的最小隔离单元。
 */
final class VehicleRuntimeInstance {

    private final String trainId;
    private final VehicleControlQueue controlQueue;
    private final VehicleSimulationQueue simulationQueue;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile long lastTick = -1;
    private volatile String lifecycleState = "READY";
    private volatile String controlQueueStatus = "IDLE";
    private volatile String simulationQueueStatus = "IDLE";
    private volatile long latencyMillis;
    private volatile String dataQuality = "GOOD";
    private volatile String reason = "READY";
    private volatile Instant updatedAt = Instant.now();

    VehicleRuntimeInstance(String trainId, VehicleRuntimeProperties properties) {
        this.trainId = trainId;
        this.controlQueue = new VehicleControlQueue(properties);
        this.simulationQueue = new VehicleSimulationQueue(properties);
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

    StepResult step(
        long tick,
        double deltaSeconds,
        TrainStateSnapshot train,
        MovementAuthoritySnapshot authority,
        TrackConstraintSnapshot track,
        PowerConstraintSnapshot power
    ) {
        Instant startedAt = Instant.now();
        if (!inFlight.compareAndSet(false, true)) {
            // 同车上一 tick 未完成时直接拒绝，避免队列积压导致中央状态倒挂。
            return reject("INSTANCE_BUSY", startedAt);
        }
        try {
            if (tick <= lastTick) {
                // 过期或重复 tick 不再重算，中央应按响应质量决定是否 fallback。
                return reject("STALE_OR_DUPLICATE_TICK", startedAt);
            }
            controlQueueStatus = "RUNNING";
            VehiclePhysicsInputDto input = controlQueue.control(tick, deltaSeconds, train, authority, track, power);
            controlQueueStatus = "DONE";
            simulationQueueStatus = "RUNNING";
            VehiclePhysicsOutputDto output = simulationQueue.step(tick, input);
            simulationQueueStatus = "DONE";
            TrainStateReportDto report = buildReport(train, input, output);
            lastTick = tick;
            lifecycleState = "RUNNING";
            dataQuality = "GOOD";
            reason = "OK";
            latencyMillis = Duration.between(startedAt, Instant.now()).toMillis();
            updatedAt = Instant.now();
            return new StepResult(Optional.of(output), Optional.of(report), state());
        } catch (VehicleRuntimeQueue.QueueRejectedException exception) {
            return reject(exception.getMessage(), startedAt);
        } catch (RuntimeException exception) {
            return reject(exception.getClass().getSimpleName(), startedAt);
        } finally {
            inFlight.set(false);
        }
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

    private StepResult reject(String rejectReason, Instant startedAt) {
        lifecycleState = "FAULT";
        dataQuality = "INVALID";
        reason = rejectReason;
        controlQueueStatus = "REJECTED";
        simulationQueueStatus = "REJECTED";
        latencyMillis = Duration.between(startedAt, Instant.now()).toMillis();
        updatedAt = Instant.now();
        return new StepResult(Optional.empty(), Optional.empty(), state());
    }

    private TrainStateReportDto buildReport(
        TrainStateSnapshot train,
        VehiclePhysicsInputDto input,
        VehiclePhysicsOutputDto output
    ) {
        double loadMassKg = VehicleLoadPolicy.loadMassKg(train.loadMassKg(), train.loadRate());
        String overloadStatus = VehicleLoadPolicy.overloadStatus(loadMassKg);
        int availableTractionCount = VehicleLoadPolicy.normalizeUnitCount(train.availableTractionCount(), VehicleLoadPolicy.NOMINAL_TRACTION_UNITS);
        int availableBrakeCount = VehicleLoadPolicy.normalizeUnitCount(train.availableBrakeCount(), VehicleLoadPolicy.NOMINAL_BRAKE_UNITS);
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
            resolveVehicleProtectionReason(train, overloadStatus),
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
            output.faultCode()
        );
    }

    private String resolveOperationMode(VehiclePhysicsInputDto input) {
        if (input.emergencyBrakeCommand()) {
            return "ATP_BRAKE";
        }
        if ("STATION_BRAKE".equals(input.dynamicsState()) || "STATION_STOPPED".equals(input.dynamicsState())) {
            return "STATION_CONTROL";
        }
        if ("POWER_DERATED".equals(input.dynamicsState()) || "OVERLOAD_DERATED".equals(input.dynamicsState())) {
            return "DEGRADED";
        }
        return "ATO";
    }

    private String resolveTractionState(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (input.tractionCommand() <= 0 || output.tractionForceNewtons() <= 0) {
            return "IDLE";
        }
        if ("POWER_DERATED".equals(input.dynamicsState()) || "OVERLOAD_DERATED".equals(input.dynamicsState()) || input.powerAvailableWatts() < 3_200_000) {
            return "DERATED";
        }
        return "APPLYING";
    }

    private String resolveBrakeState(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (input.emergencyBrakeCommand()) {
            return "EMERGENCY";
        }
        if (output.regenBrakeForceNewtons() > 0) {
            return "REGENERATIVE";
        }
        return output.brakeForceNewtons() > 0 ? "SERVICE" : "RELEASED";
    }

    private String resolveCurrentCollectionStatus(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if ("CURRENT_COLLECTION_LOST".equals(output.faultCode()) || input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) {
            return "LOST";
        }
        if ("LOW_VOLTAGE".equals(output.faultCode()) || input.railVoltage() < 1000) {
            return "LOW_VOLTAGE";
        }
        return "NORMAL";
    }

    private String resolveSelfCheckStatus(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (!input.doorClosed() || "CURRENT_COLLECTION_LOST".equals(output.faultCode()) || input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) {
            return "FAIL";
        }
        return "OK".equals(output.faultCode()) ? "PASS" : "WARN";
    }

    private int resolveFaultLevel(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        if (input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) {
            return 3;
        }
        if ("OVERLOAD_DERATED".equals(input.dynamicsState())) {
            return 1;
        }
        return switch (output.faultCode()) {
            case "OK" -> input.emergencyBrakeCommand() ? 3 : 0;
            case "LOW_VOLTAGE", "TRACTION_UNAVAILABLE", "FMU_STEP_FAILED", "EXTERNAL_SIM_FALLBACK" -> 2;
            case "CURRENT_COLLECTION_LOST", "BRAKE_UNAVAILABLE", "DOOR_NOT_LOCKED", "ATP_BRAKE" -> 3;
            default -> 2;
        };
    }

    private String resolveAvailableOperationMode(VehiclePhysicsInputDto input, VehiclePhysicsOutputDto output) {
        int faultLevel = resolveFaultLevel(input, output);
        if (faultLevel >= 3) {
            return "NO_DEPARTURE";
        }
        return faultLevel > 0 ? "DEGRADED" : "NORMAL";
    }

    private String resolveDataQuality(VehiclePhysicsOutputDto output) {
        return "OK".equals(output.faultCode()) ? "GOOD" : "INVALID";
    }

    private String resolveVehicleProtectionReason(TrainStateSnapshot train, String overloadStatus) {
        String overloadReason = VehicleLoadPolicy.vehicleProtectionReason(overloadStatus);
        if (!"NONE".equals(overloadReason)) {
            return overloadReason;
        }
        return train.vehicleProtectionReason() == null || train.vehicleProtectionReason().isBlank()
            ? "NONE"
            : train.vehicleProtectionReason();
    }

    record StepResult(
        Optional<VehiclePhysicsOutputDto> output,
        Optional<TrainStateReportDto> report,
        VehicleRuntimeInstanceState state
    ) {
    }
}
