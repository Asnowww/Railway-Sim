package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehiclePhysicsMode;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.DispatchConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.PowerNetworkTrainPosition;
import com.railwaysim.vehicleruntime.model.PowerNetworkSectionLoadRequest;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrainStateReportDto;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.CentralTrainRegistrationRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchResponse;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeBootstrapRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeEvent;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeHealth;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeInstanceState;
import com.railwaysim.vehicleruntime.model.VehicleParameterMetadata;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 外部车辆运行时总入口，集中管理每车实例和同步 step-fleet 调度。
 */
@Service
public class VehicleRuntimeManager {

    private static final long FLEET_DEADLINE_MILLIS = 100;

    private final VehicleRuntimeProperties properties;
    private final VehicleParameters vehicleParameters;
    private final PowerNetworkLoadClient powerNetworkLoadClient;
    private final CentralTrainRegistrationClient centralTrainRegistrationClient;
    private final FmuHttpVehiclePhysicsExecutor fmuExecutor;
    private final JavaFallbackVehiclePhysicsExecutor javaFallbackExecutor;
    private final Map<String, VehicleRuntimeInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, FmuInstanceSessionState> fmuSessions = new ConcurrentHashMap<>();
    private final Map<String, PowerConstraintSnapshot> authoritativePowerByTrain = new ConcurrentHashMap<>();
    private final List<VehicleRuntimeEvent> events = new ArrayList<>();
    private volatile VehicleRuntimeHealth latestHealth;
    private long totalFleetTickCount;
    private long missedDeadlineCount;
    private long fallbackEventCount;
    private long fmiErrorCount;

    public VehicleRuntimeManager(
        VehicleRuntimeProperties properties,
        VehicleParameters vehicleParameters,
        PowerNetworkLoadClient powerNetworkLoadClient,
        CentralTrainRegistrationClient centralTrainRegistrationClient,
        FmuHttpVehiclePhysicsExecutor fmuExecutor,
        JavaFallbackVehiclePhysicsExecutor javaFallbackExecutor
    ) {
        this.properties = properties;
        this.vehicleParameters = vehicleParameters;
        this.powerNetworkLoadClient = powerNetworkLoadClient;
        this.centralTrainRegistrationClient = centralTrainRegistrationClient;
        this.fmuExecutor = fmuExecutor;
        this.javaFallbackExecutor = javaFallbackExecutor;
        this.latestHealth = healthSnapshot("UP", 0, "GOOD", "READY", 0);
    }

    public VehicleRuntimeHealth health() {
        return new VehicleRuntimeHealth(
            "EXTERNAL_HTTP",
            latestHealth.heartbeatStatus(),
            Instant.now(),
            latestHealth.latencyMillis(),
            latestHealth.dataQuality(),
            instances.size(),
            latestHealth.reason(),
            properties.getPhysicsMode().name(),
            properties.getFmuModelVersion(),
            vehicleParameters.parameterSetId(),
            latestHealth.fmuBatchLatencyMillis(),
            fallbackTrainCount(),
            totalFleetTickCount,
            missedDeadlineCount,
            fallbackEventCount,
            fmiErrorCount
        );
    }

    public VehicleParameterMetadata parameterMetadata() {
        return new VehicleParameterMetadata(
            vehicleParameters.parameterSchemaVersion(),
            vehicleParameters.parameterSetId(),
            vehicleParameters.curveSetId(),
            vehicleParameters.sourcePath().toString(),
            vehicleParameters.trainType(),
            vehicleParameters.lengthMeters(),
            String.join("-", vehicleParameters.formation().order()),
            vehicleParameters.formation().motorCount(),
            vehicleParameters.formation().axleCount(),
            vehicleParameters.drivetrain().wheelRadiusMeters(),
            vehicleParameters.drivetrain().gearRatio(),
            vehicleParameters.emptyMassKg(),
            vehicleParameters.maxLoadMassKg(),
            java.util.Map.of(
                "AW0", vehicleParameters.formation().loadCasesKg().aw0(),
                "AW2", vehicleParameters.formation().loadCasesKg().aw2(),
                "AW3", vehicleParameters.formation().loadCasesKg().aw3()
            ),
            vehicleParameters.formation().hardMassLimitKg(),
            vehicleParameters.curves().pointCount(),
            vehicleParameters.maxCurveMechanicalTractionPowerWatts(),
            vehicleParameters.drivetrain().tractionTotalEfficiency(),
            vehicleParameters.drivetrain().regenTotalEfficiency(),
            vehicleParameters.curves().referenceVoltageVolts()
        );
    }

    public synchronized VehicleRuntimeHealth bootstrap(VehicleRuntimeBootstrapRequest request) {
        if (request != null) {
            properties.setDefaultLineLengthMeters(request.defaultLineLengthMeters());
            properties.setDefaultSpeedLimitMetersPerSecond(request.defaultSpeedLimitMetersPerSecond());
            properties.setSafetyGapMeters(request.safetyGapMeters());
            properties.setPowerNetworkBaseUrl(request.powerNetworkBaseUrl());
            properties.setForwardPowerLoads(request.forwardPowerLoads());
        }
        recordEvent("runtime", "BOOTSTRAP", "external vehicle runtime bootstrapped");
        latestHealth = healthSnapshot("UP", 0, "GOOD", "BOOTSTRAPPED", 0);
        return health();
    }

    public VehicleRuntimeInstanceState register(TrainStateSnapshot train) {
        String trainId = train == null ? null : train.id();
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        VehicleRuntimeInstance instance = instances.computeIfAbsent(
            trainId,
            id -> new VehicleRuntimeInstance(id, properties, vehicleParameters)
        );
        instance.launch();
        recordEvent(trainId, "REGISTER", "vehicle runtime instance registered");
        return instance.state();
    }

    public synchronized VehicleRuntimeLaunchResponse launch(VehicleRuntimeLaunchRequest request) {
        if (request == null || request.normalizedTrainId().isBlank()) {
            throw new IllegalArgumentException("trainId or trainNo is required");
        }
        String trainId = request.normalizedTrainId();
        VehicleRuntimeInstance instance = instances.computeIfAbsent(
            trainId,
            id -> new VehicleRuntimeInstance(id, properties, vehicleParameters)
        );
        // 启动顺序固定：先创建车辆仿真实例，再唤醒本车控制实例，最后向中央登记镜像。
        instance.launch();
        recordEvent(trainId, "LAUNCH", "vehicle simulation instance launched and control instance awakened");
        String registrationStatus = "SKIPPED";
        String reason = "CENTRAL_REGISTRATION_SKIPPED";
        String dataQuality = "GOOD";
        if (request.shouldRegisterWithCentral()) {
            try {
                centralTrainRegistrationClient.register(
                    CentralTrainRegistrationRequest.from(request, vehicleParameters)
                );
                registrationStatus = "REGISTERED";
                reason = "CENTRAL_REGISTERED";
                recordEvent(trainId, "CENTRAL_REGISTERED", "vehicle runtime registered train with central system");
            } catch (RuntimeException exception) {
                registrationStatus = "FAILED";
                reason = "CENTRAL_REGISTRATION_FAILED:" + summarize(exception);
                dataQuality = "DEGRADED";
                recordEvent(trainId, "CENTRAL_REGISTRATION_FAILED", summarize(exception));
            }
        }
        latestHealth = healthSnapshot("UP", 0, dataQuality, reason, 0);
        return new VehicleRuntimeLaunchResponse(trainId, instance.state(), registrationStatus, reason);
    }

    public void remove(String trainId) {
        if (trainId != null && instances.remove(trainId) != null) {
            fmuSessions.remove(trainId);
            javaFallbackExecutor.deleteInstance(trainId);
            if (properties.getPhysicsMode() == VehiclePhysicsMode.FMU_HTTP) {
                try {
                    fmuExecutor.deleteInstance(trainId);
                } catch (RuntimeException exception) {
                    recordEvent(trainId, "FMU_DELETE_FAILED", summarize(exception));
                }
            }
            recordEvent(trainId, "REMOVE", "vehicle runtime instance removed");
        }
    }

    public void clear() {
        instances.clear();
        fmuSessions.clear();
        javaFallbackExecutor.resetAll();
        if (properties.getPhysicsMode() == VehiclePhysicsMode.FMU_HTTP) {
            try {
                fmuExecutor.resetAll();
            } catch (RuntimeException exception) {
                recordEvent("runtime", "FMU_RESET_ALL_FAILED", summarize(exception));
            }
        }
        recordEvent("runtime", "CLEAR", "all vehicle runtime instances cleared");
    }

    public void resetPhysics(String trainId) {
        requireInstance(trainId);
        fmuSessions.put(trainId, FmuInstanceSessionState.RESET_PENDING);
        javaFallbackExecutor.deleteInstance(trainId);
        recordEvent(trainId, "FMU_RESET_PENDING", "FMU reset will use the next authoritative vehicle state");
    }

    public void resyncPhysics(String trainId) {
        requireInstance(trainId);
        fmuSessions.put(trainId, FmuInstanceSessionState.RESYNC_PENDING);
        javaFallbackExecutor.deleteInstance(trainId);
        recordEvent(trainId, "FMU_RESYNC_PENDING", "FMU resync will use the next authoritative vehicle state");
    }

    public void resyncAllPhysics() {
        instances.keySet().forEach(trainId -> fmuSessions.put(trainId, FmuInstanceSessionState.RESYNC_PENDING));
        javaFallbackExecutor.resetAll();
        recordEvent("runtime", "FMU_RESYNC_ALL_PENDING", "all FMU instances will resync on the next tick");
    }

    public List<VehicleRuntimeInstanceState> instances() {
        return instances.values().stream()
            .map(VehicleRuntimeInstance::state)
            .sorted(Comparator.comparing(VehicleRuntimeInstanceState::trainId))
            .toList();
    }

    /**
     * 同步执行一批列车 tick，并在成功后把车辆物理输出折算成供电分区负荷。
     */
    public synchronized VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("step request is required");
        }
        if (Math.abs(request.deltaSeconds() - 0.1) > 1.0e-9) {
            throw new IllegalArgumentException("deltaSeconds must equal 0.1");
        }
        Instant startedAt = Instant.now();
        totalFleetTickCount++;
        List<TrainStateSnapshot> trains = request.trains() == null ? List.of() : request.trains();
        java.util.Set<String> trainIds = new java.util.HashSet<>();
        for (TrainStateSnapshot train : trains) {
            if (train == null || train.id() == null || train.id().isBlank()) {
                throw new IllegalArgumentException("every train must have a trainId");
            }
            if (!trainIds.add(train.id())) {
                throw new IllegalArgumentException("duplicate trainId in fleet step: " + train.id());
            }
        }
        Map<String, MovementAuthoritySnapshot> authorityByTrain = index(request.movementAuthorities(), MovementAuthoritySnapshot::trainId);
        Map<String, TrackConstraintSnapshot> trackByTrain = index(request.trackConstraints(), TrackConstraintSnapshot::trainId);
        Map<String, DispatchConstraintSnapshot> dispatchByTrain = index(request.dispatchConstraints(), DispatchConstraintSnapshot::trainId);
        Map<String, PowerConstraintSnapshot> powerByTrain = powerConstraintsForStep(request, trains);
        Map<String, VehicleRuntimeInstance.PreparedStep> preparedByTrain = new LinkedHashMap<>();
        Map<String, VehiclePhysicsOutputDto> outputByTrain = new LinkedHashMap<>();
        Map<String, String> fallbackReasonByTrain = new LinkedHashMap<>();
        List<TrainStateReportDto> reports = new ArrayList<>();
        List<VehicleRuntimeInstanceState> states = new ArrayList<>();

        // Prepare: all control decisions are frozen before any train advances physically.
        for (TrainStateSnapshot train : trains) {
            VehicleRuntimeInstance instance = instances.computeIfAbsent(
                train.id(),
                id -> new VehicleRuntimeInstance(id, properties, vehicleParameters)
            );
            VehicleRuntimeInstance.PreparedStep prepared = instance.prepare(
                request.tick(),
                request.deltaSeconds(),
                train,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                dispatchByTrain.get(train.id()),
                powerByTrain.get(train.id())
            );
            if (prepared != null) {
                preparedByTrain.put(train.id(), prepared);
                instance.markSimulationRunning();
            }
        }

        long fmuBatchLatencyMillis = 0;
        if (properties.getPhysicsMode() == VehiclePhysicsMode.FMU_HTTP && !preparedByTrain.isEmpty()) {
            Map<String, VehiclePhysicsInputDto> remoteInputs = new LinkedHashMap<>();
            Map<String, VehiclePhysicsInputDto> localInputs = new LinkedHashMap<>();
            preparedByTrain.forEach((trainId, prepared) -> {
                FmuInstanceSessionState session = fmuSessions.getOrDefault(trainId, FmuInstanceSessionState.UNINITIALIZED);
                if (session == FmuInstanceSessionState.FALLBACK) {
                    localInputs.put(trainId, prepared.input());
                    fallbackReasonByTrain.put(trainId, "FMU_STICKY_FALLBACK");
                } else {
                    remoteInputs.put(trainId, withLifecycle(prepared.input(), lifecycleCommand(session)));
                }
            });
            if (!remoteInputs.isEmpty()) {
                Instant fmuStartedAt = Instant.now();
                try {
                    VehiclePhysicsExecutor.ExecutionResult remote = fmuExecutor.execute(executionRequest(request, remoteInputs));
                    fmuBatchLatencyMillis = remote.latencyMillis();
                    outputByTrain.putAll(remote.outputs());
                    remote.outputs().keySet().forEach(trainId -> fmuSessions.put(trainId, FmuInstanceSessionState.ACTIVE));
                    remote.errors().forEach((trainId, error) -> {
                        localInputs.put(trainId, preparedByTrain.get(trainId).input());
                        fallbackReasonByTrain.put(trainId, "FMU_TRAIN_FALLBACK:" + error.faultCode());
                        fmuSessions.put(trainId, FmuInstanceSessionState.FALLBACK);
                        fallbackEventCount++;
                        fmiErrorCount++;
                        recordEvent(trainId, "FMU_TRAIN_FALLBACK", error.faultCode() + ":" + error.message());
                    });
                } catch (RuntimeException exception) {
                    fmuBatchLatencyMillis = Duration.between(fmuStartedAt, Instant.now()).toMillis();
                    remoteInputs.forEach((trainId, ignored) -> {
                        localInputs.put(trainId, preparedByTrain.get(trainId).input());
                        fallbackReasonByTrain.put(trainId, "FMU_BATCH_FALLBACK");
                        fmuSessions.put(trainId, FmuInstanceSessionState.FALLBACK);
                    });
                    fallbackEventCount += remoteInputs.size();
                    fmiErrorCount++;
                    recordEvent("runtime", "FMU_BATCH_FALLBACK", summarize(exception));
                }
            }
            applyFallback(request, localInputs, fallbackReasonByTrain, outputByTrain, true);
        } else {
            Map<String, VehiclePhysicsInputDto> localInputs = preparedByTrain.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().input(),
                    (left, right) -> right,
                    LinkedHashMap::new
                ));
            applyFallback(request, localInputs, Map.of(), outputByTrain, false);
        }

        // Apply: all outputs are committed only after the one fleet-level physics batch returns.
        for (TrainStateSnapshot train : trains) {
            VehicleRuntimeInstance instance = instances.get(train.id());
            VehicleRuntimeInstance.PreparedStep prepared = preparedByTrain.get(train.id());
            VehiclePhysicsOutputDto output = outputByTrain.get(train.id());
            if (prepared == null) {
                states.add(instance.state());
                continue;
            }
            if (output == null) {
                instance.abort("PHYSICS_OUTPUT_MISSING", prepared);
                states.add(instance.state());
                continue;
            }
            String stepReason = fallbackReasonByTrain.getOrDefault(train.id(), "OK");
            VehicleRuntimeInstance.StepResult result = instance.apply(prepared, output, stepReason);
            reports.add(result.report());
            states.add(result.state());
        }

        List<VehiclePhysicsOutputDto> outputs = trains.stream()
            .map(train -> outputByTrain.get(train.id()))
            .filter(java.util.Objects::nonNull)
            .toList();
        String dataQuality = outputs.size() == trains.size()
            && reports.size() == trains.size()
            && fallbackReasonByTrain.isEmpty()
            && outputs.stream().allMatch(output -> "GOOD".equals(output.dataQuality()))
            ? "GOOD" : "DEGRADED";
        String reason = dataQuality.equals("GOOD") ? "OK"
            : fallbackReasonByTrain.isEmpty() ? "PARTIAL_STEP" : "PHYSICS_FALLBACK_ACTIVE";
        try {
            // 车辆状态变化先汇总为同分区负荷，再由权威供电仿真返回下一控制周期的约束。
            List<PowerConstraintSnapshot> nextConstraints = powerNetworkLoadClient.stepPowerNetwork(
                request.tick(),
                request.tick() * request.deltaSeconds(),
                request.deltaSeconds(),
                toSectionLoads(outputs, powerByTrain),
                positionsFromOutputs(outputs)
            );
            authoritativePowerByTrain.clear();
            nextConstraints.forEach(constraint -> authoritativePowerByTrain.put(constraint.trainId(), constraint));
        } catch (RuntimeException exception) {
            dataQuality = "DEGRADED";
            reason = "POWER_LOAD_FORWARD_FAILED";
            recordEvent("power-network", "POWER_LOAD_FORWARD_FAILED", summarize(exception));
        }
        long latency = Duration.between(startedAt, Instant.now()).toMillis();
        if (latency > FLEET_DEADLINE_MILLIS) {
            missedDeadlineCount++;
        }
        latestHealth = healthSnapshot("UP", latency, dataQuality, reason, fmuBatchLatencyMillis);
        return new VehicleRuntimeStepResponse(request.tick(), Instant.now(), dataQuality, outputs, reports, states);
    }

    public synchronized List<VehicleRuntimeEvent> events() {
        return List.copyOf(events);
    }

    private VehiclePhysicsExecutor.ExecutionRequest executionRequest(
        VehicleRuntimeStepRequest request,
        Map<String, VehiclePhysicsInputDto> inputs
    ) {
        return new VehiclePhysicsExecutor.ExecutionRequest(
            request.tick(),
            request.tick() * request.deltaSeconds(),
            request.deltaSeconds(),
            properties.getFmuModelVersion(),
            vehicleParameters.parameterSetId(),
            "vehicle-runtime-" + request.tick(),
            List.copyOf(inputs.values())
        );
    }

    private void applyFallback(
        VehicleRuntimeStepRequest request,
        Map<String, VehiclePhysicsInputDto> inputs,
        Map<String, String> fallbackReasons,
        Map<String, VehiclePhysicsOutputDto> outputs,
        boolean markExternalFallback
    ) {
        if (inputs.isEmpty()) {
            return;
        }
        VehiclePhysicsExecutor.ExecutionResult local = javaFallbackExecutor.execute(executionRequest(request, inputs));
        local.outputs().forEach((trainId, output) -> outputs.put(
            trainId,
            markExternalFallback && fallbackReasons.containsKey(trainId) ? asExternalFallback(output) : output
        ));
        local.errors().forEach((trainId, error) ->
            recordEvent(trainId, "JAVA_FALLBACK_FAILED", error.message())
        );
    }

    private VehiclePhysicsOutputDto asExternalFallback(VehiclePhysicsOutputDto output) {
        return new VehiclePhysicsOutputDto(
            output.trainId(),
            output.newPositionMeters(),
            output.newSpeedMetersPerSecond(),
            output.accelerationMetersPerSecondSquared(),
            output.tractionForceNewtons(),
            output.brakeForceNewtons(),
            output.regenBrakeForceNewtons(),
            output.motorSpeedRpm(),
            output.interpolatedTractionTorqueNmPerMotor(),
            output.interpolatedBrakeTorqueNmPerMotor(),
            output.airBrakeForceNewtons(),
            output.mechanicalTractionPowerWatts(),
            output.tractionPowerWatts(),
            output.railCurrentAmps(),
            output.mechanicalRegenPowerWatts(),
            output.regenPowerWatts(),
            output.energyConsumedKwh(),
            output.energyRegeneratedKwh(),
            "EXTERNAL_SIM_FALLBACK",
            "FALLBACK",
            "DEGRADED",
            "ERROR"
        );
    }

    private VehiclePhysicsInputDto withLifecycle(VehiclePhysicsInputDto input, String lifecycleCommand) {
        return new VehiclePhysicsInputDto(
            input.trainId(), lifecycleCommand, input.sectionId(), input.positionMeters(), input.speedMetersPerSecond(),
            input.trainMassKg(), input.tractionCommand(), input.brakeCommand(), input.emergencyBrakeCommand(),
            input.speedLimitMetersPerSecond(), input.movementAuthorityDistanceMeters(), input.gradient(),
            input.curveRadiusMeters(), input.railVoltage(), input.powerAvailableWatts(), input.regenPowerAvailableWatts(),
            input.currentCollectionAvailable(), input.doorClosed(), input.adhesionCoefficient(),
            input.previousEnergyConsumedKwh(), input.previousEnergyRegeneratedKwh(), input.deltaSeconds(),
            input.dynamicsState(), input.dynamicsConstraintReason(), input.stationDistanceMeters(),
            input.stoppingDistanceMeters()
        );
    }

    private String lifecycleCommand(FmuInstanceSessionState session) {
        return switch (session) {
            case UNINITIALIZED -> "INIT";
            case ACTIVE -> "STEP";
            case RESET_PENDING -> "RESET";
            case RESYNC_PENDING -> "RESYNC";
            case FALLBACK -> throw new IllegalStateException("fallback session must not be sent to FMU");
        };
    }

    private VehicleRuntimeHealth healthSnapshot(
        String heartbeatStatus,
        long latencyMillis,
        String dataQuality,
        String reason,
        long fmuBatchLatencyMillis
    ) {
        return new VehicleRuntimeHealth(
            "EXTERNAL_HTTP",
            heartbeatStatus,
            Instant.now(),
            latencyMillis,
            dataQuality,
            instances.size(),
            reason,
            properties.getPhysicsMode().name(),
            properties.getFmuModelVersion(),
            vehicleParameters.parameterSetId(),
            fmuBatchLatencyMillis,
            fallbackTrainCount(),
            totalFleetTickCount,
            missedDeadlineCount,
            fallbackEventCount,
            fmiErrorCount
        );
    }

    private int fallbackTrainCount() {
        return (int) fmuSessions.values().stream()
            .filter(state -> state == FmuInstanceSessionState.FALLBACK)
            .count();
    }

    private void requireInstance(String trainId) {
        if (trainId == null || !instances.containsKey(trainId)) {
            throw new IllegalArgumentException("unknown train instance: " + trainId);
        }
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> keyFn) {
        return (values == null ? List.<T>of() : values).stream()
            .filter(value -> keyFn.apply(value) != null)
            .collect(Collectors.toMap(keyFn, Function.identity(), (left, right) -> right));
    }

    private Map<String, PowerConstraintSnapshot> powerConstraintsForStep(
        VehicleRuntimeStepRequest request,
        List<TrainStateSnapshot> trains
    ) {
        if (!powerNetworkLoadClient.enabled()) {
            return index(request.powerConstraints(), PowerConstraintSnapshot::trainId);
        }
        List<PowerConstraintSnapshot> constraints = powerNetworkLoadClient.queryConstraints(positionsFromTrains(trains));
        if (!constraints.isEmpty()) {
            authoritativePowerByTrain.clear();
            constraints.forEach(constraint -> authoritativePowerByTrain.put(constraint.trainId(), constraint));
        }
        return Map.copyOf(authoritativePowerByTrain);
    }

    private List<PowerNetworkTrainPosition> positionsFromTrains(List<TrainStateSnapshot> trains) {
        return trains.stream()
            .map(train -> new PowerNetworkTrainPosition(train.id(), train.positionMeters()))
            .toList();
    }

    private List<PowerNetworkTrainPosition> positionsFromOutputs(List<VehiclePhysicsOutputDto> outputs) {
        return outputs.stream()
            .map(output -> new PowerNetworkTrainPosition(output.trainId(), output.newPositionMeters()))
            .toList();
    }

    private List<PowerNetworkSectionLoadRequest> toSectionLoads(
        List<VehiclePhysicsOutputDto> outputs,
        Map<String, PowerConstraintSnapshot> powerByTrain
    ) {
        // 权威供电仿真按列车位置返回分区，车辆运行时只消费该结果并汇总同分区负荷。
        Map<String, SectionLoadAccumulator> loads = new ConcurrentHashMap<>();
        for (VehiclePhysicsOutputDto output : outputs) {
            PowerConstraintSnapshot power = powerByTrain.get(output.trainId());
            if (power == null || power.sectionId() == null || power.sectionId().isBlank()) {
                continue;
            }
            loads.computeIfAbsent(power.sectionId(), SectionLoadAccumulator::new).add(output);
        }
        return loads.values().stream()
            .map(SectionLoadAccumulator::toRequest)
            .toList();
    }

    private synchronized void recordEvent(String trainId, String type, String detail) {
        events.add(new VehicleRuntimeEvent(type + "-" + Instant.now().toEpochMilli(), trainId, type, detail, Instant.now()));
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static final class SectionLoadAccumulator {
        private final String powerSectionId;
        private final List<String> trainIds = new ArrayList<>();
        private double tractionPowerWatts;
        private double regenPowerWatts;
        private double currentAmps;

        private SectionLoadAccumulator(String powerSectionId) {
            this.powerSectionId = powerSectionId;
        }

        private void add(VehiclePhysicsOutputDto output) {
            trainIds.add(output.trainId());
            tractionPowerWatts += output.tractionPowerWatts();
            regenPowerWatts += output.regenPowerWatts();
            currentAmps += output.railCurrentAmps();
        }

        private PowerNetworkSectionLoadRequest toRequest() {
            return new PowerNetworkSectionLoadRequest(
                powerSectionId,
                List.copyOf(trainIds),
                tractionPowerWatts,
                regenPowerWatts,
                currentAmps
            );
        }
    }
}
