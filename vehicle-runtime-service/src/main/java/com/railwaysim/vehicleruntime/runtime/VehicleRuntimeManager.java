package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehiclePhysicsMode;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.config.StoppingControlProperties;
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
import com.railwaysim.vehicleruntime.model.VehicleTelemetryModeRequest;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryModeState;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryRequest;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final DriverCommandHolder driverCommandHolder;
    private final StoppingControlProperties stoppingProperties;
    private final VehicleTelemetryCoordinator telemetryCoordinator;
    private final Map<String, VehicleRuntimeInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, FmuInstanceSessionState> fmuSessions = new ConcurrentHashMap<>();
    private final Map<String, PowerConstraintSnapshot> authoritativePowerByTrain = new ConcurrentHashMap<>();
    private final List<VehicleRuntimeEvent> events = new ArrayList<>();
    private volatile VehicleRuntimeHealth latestHealth;
    private long totalFleetTickCount;
    private long missedDeadlineCount;
    private long fallbackEventCount;
    private long fmiErrorCount;
    private volatile String currentRunId = "";
    private volatile long lastAcceptedTick = -1;
    private boolean bootstrapped;
    private List<TrainStateHolder.StationDef> stationDefinitions = List.of();

    @Autowired
    public VehicleRuntimeManager(
        VehicleRuntimeProperties properties,
        VehicleParameters vehicleParameters,
        PowerNetworkLoadClient powerNetworkLoadClient,
        CentralTrainRegistrationClient centralTrainRegistrationClient,
        FmuHttpVehiclePhysicsExecutor fmuExecutor,
        JavaFallbackVehiclePhysicsExecutor javaFallbackExecutor,
        DriverCommandHolder driverCommandHolder,
        StoppingControlProperties stoppingProperties,
        VehicleTelemetryCoordinator telemetryCoordinator
    ) {
        this.properties = properties;
        this.vehicleParameters = vehicleParameters;
        this.powerNetworkLoadClient = powerNetworkLoadClient;
        this.centralTrainRegistrationClient = centralTrainRegistrationClient;
        this.fmuExecutor = fmuExecutor;
        this.javaFallbackExecutor = javaFallbackExecutor;
        this.driverCommandHolder = driverCommandHolder;
        this.stoppingProperties = stoppingProperties;
        this.telemetryCoordinator = telemetryCoordinator;
        this.latestHealth = healthSnapshot("UP", 0, "GOOD", "READY", 0);
    }

    public VehicleRuntimeManager(
        VehicleRuntimeProperties properties,
        VehicleParameters vehicleParameters,
        PowerNetworkLoadClient powerNetworkLoadClient,
        CentralTrainRegistrationClient centralTrainRegistrationClient,
        FmuHttpVehiclePhysicsExecutor fmuExecutor,
        JavaFallbackVehiclePhysicsExecutor javaFallbackExecutor
    ) {
        this(properties, vehicleParameters, powerNetworkLoadClient, centralTrainRegistrationClient,
            fmuExecutor, javaFallbackExecutor, DriverCommandHolder.getInstance(),
            new StoppingControlProperties(), new VehicleTelemetryCoordinator(properties));
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
            fmiErrorCount,
            currentRunId,
            lastAcceptedTick,
            "NOT_APPLICABLE",
            configHash(),
            stoppingProperties.getParameterVersion(),
            bootstrapped
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
            stationDefinitions = request.stations().stream()
                .map(station -> new TrainStateHolder.StationDef(
                    station.id(), station.name(), station.positionMeters(), station.platformIds()))
                .toList();
            bootstrapped = true;
        }
        recordEvent("runtime", "BOOTSTRAP", "external vehicle runtime bootstrapped");
        latestHealth = healthSnapshot("UP", 0, "GOOD", "BOOTSTRAPPED", 0);
        return health();
    }

    public synchronized VehicleRuntimeInstanceState register(TrainStateSnapshot train) {
        String trainId = train == null ? null : train.id();
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        VehicleRuntimeInstance instance = instances.computeIfAbsent(
            trainId,
            id -> new VehicleRuntimeInstance(
                id, properties, vehicleParameters, driverCommandHolder, stoppingProperties,
                stationDefinitions)
        );
        instance.initializeState(train);
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
            id -> new VehicleRuntimeInstance(
                id, properties, vehicleParameters, driverCommandHolder, stoppingProperties,
                stationDefinitions)
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

    public synchronized void remove(String trainId) {
        if (trainId != null && instances.remove(trainId) != null) {
            telemetryCoordinator.remove(trainId);
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

    public synchronized void clear() {
        instances.clear();
        telemetryCoordinator.clear();
        fmuSessions.clear();
        javaFallbackExecutor.resetAll();
        currentRunId = "";
        lastAcceptedTick = -1;
        if (properties.getPhysicsMode() == VehiclePhysicsMode.FMU_HTTP) {
            try {
                fmuExecutor.resetAll();
            } catch (RuntimeException exception) {
                recordEvent("runtime", "FMU_RESET_ALL_FAILED", summarize(exception));
            }
        }
        recordEvent("runtime", "CLEAR", "all vehicle runtime instances cleared");
    }

    public synchronized void resetPhysics(String trainId) {
        requireInstance(trainId);
        fmuSessions.put(trainId, FmuInstanceSessionState.RESET_PENDING);
        javaFallbackExecutor.deleteInstance(trainId);
        recordEvent(trainId, "FMU_RESET_PENDING", "FMU reset will use the next authoritative vehicle state");
    }

    public synchronized void resyncPhysics(String trainId) {
        requireInstance(trainId);
        fmuSessions.put(trainId, FmuInstanceSessionState.RESYNC_PENDING);
        javaFallbackExecutor.deleteInstance(trainId);
        recordEvent(trainId, "FMU_RESYNC_PENDING", "FMU resync will use the next authoritative vehicle state");
    }

    public synchronized void resyncAllPhysics() {
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

    public boolean hasInstance(String trainId) {
        return trainId != null && instances.containsKey(trainId);
    }

    /**
     * 同步执行一批列车 tick，并在成功后把车辆物理输出折算成供电分区负荷。
     * <p>
     * 双模兼容：若 request 含 trains 字段（旧模式），从 request 读取状态并初始化 TrainStateHolder；
     * 若不含（新模式），从本地 TrainStateHolder 读取权威状态。
     */
    public synchronized VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("step request is required");
        }
        if (request.simulationRunId() == null || request.simulationRunId().isBlank()) {
            throw new IllegalArgumentException("simulationRunId is required");
        }
        if (Math.abs(request.deltaSeconds() - 0.1) > 1.0e-9) {
            throw new IllegalArgumentException("deltaSeconds must equal 0.1");
        }
        Instant startedAt = Instant.now();
        totalFleetTickCount++;
        rolloverRunIfRequired(request.simulationRunId(), request.tick());

        // 双模检测：是否从旧格式接收列车状态
        boolean hasExplicitTrains = request.trains() != null && !request.trains().isEmpty();

        // 构建参与本次 tick 的列车 ID 集合
        List<String> activeTrainIds;
        if (hasExplicitTrains) {
            // 先完整校验再创建实例，避免非法批次留下半初始化权威状态。
            java.util.LinkedHashSet<String> validatedTrainIds = new java.util.LinkedHashSet<>();
            for (TrainStateSnapshot train : request.trains()) {
                if (train == null || train.id() == null || train.id().isBlank()) {
                    throw new IllegalArgumentException("every train must have a trainId");
                }
                if (!validatedTrainIds.add(train.id())) {
                    throw new IllegalArgumentException("duplicate trainId in fleet step: " + train.id());
                }
            }
            // 旧模式：从 request.trains 确定列车列表，初始化 TrainStateHolder
            activeTrainIds = new ArrayList<>(validatedTrainIds);
            for (TrainStateSnapshot train : request.trains()) {
                VehicleRuntimeInstance instance = instances.computeIfAbsent(
                    train.id(),
                    id -> new VehicleRuntimeInstance(
                        id, properties, vehicleParameters, driverCommandHolder, stoppingProperties,
                        stationDefinitions)
                );
                instance.initializeState(train);
            }
        } else {
            // 新模式：从已注册实例确定列车列表，每个实例须有已初始化的 TrainStateHolder
            activeTrainIds = new ArrayList<>(instances.keySet());
        }

        telemetryCoordinator.applyAtTick(
            request.simulationRunId(), request.tick(), activeTrainIds, instances, startedAt
        );

        Map<String, MovementAuthoritySnapshot> authorityByTrain = index(request.movementAuthorities(), MovementAuthoritySnapshot::trainId);
        Map<String, TrackConstraintSnapshot> trackByTrain = index(request.trackConstraints(), TrackConstraintSnapshot::trainId);
        Map<String, DispatchConstraintSnapshot> dispatchByTrain = index(request.dispatchConstraints(), DispatchConstraintSnapshot::trainId);
        Map<String, PowerConstraintSnapshot> powerByTrain = powerConstraintsForStep(request, activeTrainIds);
        Map<String, VehicleRuntimeInstance.PreparedStep> preparedByTrain = new LinkedHashMap<>();
        Map<String, VehiclePhysicsOutputDto> outputByTrain = new LinkedHashMap<>();
        Map<String, String> fallbackReasonByTrain = new LinkedHashMap<>();
        List<TrainStateReportDto> reports = new ArrayList<>();
        List<VehicleRuntimeInstanceState> instanceStates = new ArrayList<>();
        List<TrainStateSnapshot> resultTrainStates = new ArrayList<>();
        List<VehicleRuntimeEvent> stepEvents = new ArrayList<>();

        // Prepare: all control decisions are frozen before any train advances physically.
        for (String trainId : activeTrainIds) {
            VehicleRuntimeInstance instance = instances.get(trainId);
            if (instance == null) {
                // 新模式下列车未在 instances 中注册的跳过（不应发生）
                continue;
            }
            VehicleRuntimeInstance.PreparedStep prepared = instance.prepare(
                request.tick(),
                request.deltaSeconds(),
                authorityByTrain.get(trainId),
                trackByTrain.get(trainId),
                dispatchByTrain.get(trainId),
                powerByTrain.get(trainId)
            );
            if (prepared != null) {
                preparedByTrain.put(trainId, prepared);
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
        // Collect pre-step snapshots for event detection
        Map<String, TrainStateSnapshot> preStepSnapshots = new LinkedHashMap<>();
        for (String trainId : activeTrainIds) {
            VehicleRuntimeInstance instance = instances.get(trainId);
            if (instance != null) {
                preStepSnapshots.put(trainId, instance.snapshotTrainState());
            }
        }
        for (String trainId : activeTrainIds) {
            VehicleRuntimeInstance instance = instances.get(trainId);
            VehicleRuntimeInstance.PreparedStep prepared = preparedByTrain.get(trainId);
            VehiclePhysicsOutputDto output = outputByTrain.get(trainId);
            if (prepared == null) {
                instanceStates.add(instance != null ? instance.state() : null);
                resultTrainStates.add(instance != null ? instance.snapshotTrainState() : null);
                continue;
            }
            if (output == null) {
                instance.abort("PHYSICS_OUTPUT_MISSING", prepared);
                instanceStates.add(instance.state());
                resultTrainStates.add(instance.snapshotTrainState());
                continue;
            }
            String stepReason = fallbackReasonByTrain.getOrDefault(trainId, "OK");
            VehicleRuntimeInstance.StepResult result = instance.apply(prepared, output, stepReason);
            reports.add(result.report());
            instanceStates.add(result.state());
            resultTrainStates.add(result.trainState());
            // 事件检测
            TrainStateSnapshot before = preStepSnapshots.get(trainId);
            TrainStateSnapshot after = result.trainState();
            if (before != null && after != null) {
                stepEvents.addAll(detectEvents(trainId, request.tick(), before, after));
            }
        }

        List<VehiclePhysicsOutputDto> outputs = activeTrainIds.stream()
            .map(outputByTrain::get)
            .filter(java.util.Objects::nonNull)
            .toList();
        int expectedCount = activeTrainIds.size();
        String dataQuality = outputs.size() == expectedCount
            && reports.size() == expectedCount
            && fallbackReasonByTrain.isEmpty()
            && outputs.stream().allMatch(output -> "GOOD".equals(output.dataQuality()))
            ? "GOOD" : "DEGRADED";
        String reason = dataQuality.equals("GOOD") ? "OK"
            : fallbackReasonByTrain.isEmpty() ? "PARTIAL_STEP" : "PHYSICS_FALLBACK_ACTIVE";
        try {
            // 车辆状态变化先汇总为同分区负荷，再由权威供电仿真返回下一控制周期的约束。
            List<PowerConstraintSnapshot> nextConstraints = powerNetworkLoadClient.stepPowerNetwork(
                request.simulationRunId(),
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
        currentRunId = request.simulationRunId();
        lastAcceptedTick = request.tick();
        latestHealth = healthSnapshot("UP", latency, dataQuality, reason, fmuBatchLatencyMillis);
        return new VehicleRuntimeStepResponse(
            request.tick(), Instant.now(), dataQuality,
            outputs, reports, instanceStates, resultTrainStates, stepEvents);
    }

    private void rolloverRunIfRequired(String incomingRunId, long incomingTick) {
        if (currentRunId.isBlank()) {
            telemetryCoordinator.rollover();
            currentRunId = incomingRunId;
            return;
        }
        if (currentRunId.equals(incomingRunId)) {
            return;
        }
        if (incomingTick > 1) {
            throw new IllegalArgumentException(
                "VEHICLE_RUN_ID_MISMATCH: new simulation run must start at tick 0 or 1"
            );
        }
        String previousRunId = currentRunId;
        telemetryCoordinator.rollover();
        currentRunId = incomingRunId;
        lastAcceptedTick = -1;
        authoritativePowerByTrain.clear();
        driverCommandHolder.clear();
        instances.values().forEach(VehicleRuntimeInstance::rolloverRun);
        instances.keySet().forEach(trainId -> fmuSessions.put(trainId, FmuInstanceSessionState.RESYNC_PENDING));
        javaFallbackExecutor.resetAll();
        recordEvent("runtime", "RUN_ROLLOVER", previousRunId + " -> " + incomingRunId);
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
            fmiErrorCount,
            currentRunId,
            lastAcceptedTick,
            "NOT_APPLICABLE",
            configHash(),
            stoppingProperties.getParameterVersion(),
            bootstrapped
        );
    }

    private String configHash() {
        String canonical = String.join("|",
            properties.getPhysicsMode().name(),
            properties.getFmuModelVersion(),
            vehicleParameters.parameterSetId(),
            stoppingProperties.getParameterVersion(),
            stationDefinitions.stream()
                .map(station -> station.id() + ":" + station.centerMeters() + ":"
                    + String.join(",", station.platformIds()))
                .collect(java.util.stream.Collectors.joining(";")),
            Double.toString(stoppingProperties.getServiceBrakeDecelerationMetersPerSecondSquared()),
            Double.toString(stoppingProperties.getStationStopWindowMeters()),
            Double.toString(properties.getSafetyGapMeters()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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
        List<String> trainIds
    ) {
        if (!powerNetworkLoadClient.enabled()) {
            return index(request.powerConstraints(), PowerConstraintSnapshot::trainId);
        }
        List<PowerConstraintSnapshot> constraints = powerNetworkLoadClient.queryConstraints(
            trainIds.stream()
                .map(id -> new PowerNetworkTrainPosition(id, getTrainPosition(id)))
                .toList()
        );
        if (!constraints.isEmpty()) {
            authoritativePowerByTrain.clear();
            constraints.forEach(constraint -> authoritativePowerByTrain.put(constraint.trainId(), constraint));
        }
        return Map.copyOf(authoritativePowerByTrain);
    }

    /** 获取单车位置（供供电约束查询使用）。 */
    private double getTrainPosition(String trainId) {
        VehicleRuntimeInstance instance = instances.get(trainId);
        if (instance == null) return 0;
        TrainStateSnapshot state = instance.snapshotTrainState();
        return state != null ? state.positionMeters() : 0;
    }

    /** 获取指定列车的权威状态快照。 */
    public TrainStateSnapshot getTrainState(String trainId) {
        VehicleRuntimeInstance instance = instances.get(trainId);
        if (instance == null) {
            throw new IllegalArgumentException("unknown train instance: " + trainId);
        }
        return instance.snapshotTrainState();
    }

    /** 获取所有列车的权威状态快照。 */
    public List<TrainStateSnapshot> getAllTrainStates() {
        return instances.values().stream()
            .map(VehicleRuntimeInstance::snapshotTrainState)
            .toList();
    }

    public VehicleTelemetryResponse acceptTelemetry(VehicleTelemetryRequest request) {
        return telemetryCoordinator.accept(
            request, currentRunId, Set.copyOf(instances.keySet()), Instant.now()
        );
    }

    public VehicleTelemetryModeState configureTelemetryMode(
        String trainId,
        VehicleTelemetryModeRequest request
    ) {
        return telemetryCoordinator.configureMode(trainId, request, Set.copyOf(instances.keySet()));
    }

    public VehicleTelemetryModeState telemetryMode(String trainId) {
        requireInstance(trainId);
        return telemetryCoordinator.mode(trainId);
    }

    /** 司控台输入：应用状态变更。 */
    public void applyDriverCabInput(String trainId, com.railwaysim.vehicleruntime.drivercab.DriverCabPlcInputPacket input) {
        VehicleRuntimeInstance instance = instances.get(trainId);
        if (instance == null) {
            throw new IllegalArgumentException("unknown train instance: " + trainId);
        }
        instance.applyDriverCabInput(input);
    }

    /** 网络屏牵引切除请求：在 9300 权威运行时状态中生效。 */
    public void applyTractionCut(String trainId, boolean requested) {
        VehicleRuntimeInstance instance = instances.get(trainId);
        if (instance == null) {
            throw new IllegalArgumentException("unknown train instance: " + trainId);
        }
        instance.applyTractionCut(requested);
    }

    /** 检测本 tick 内发生的事件。 */
    private List<VehicleRuntimeEvent> detectEvents(
        String trainId, long tick,
        TrainStateSnapshot before, TrainStateSnapshot after
    ) {
        List<VehicleRuntimeEvent> events = new ArrayList<>();
        Instant now = Instant.now();

        if (Math.abs(after.positionMeters() - before.positionMeters()) > 0.01) {
            events.add(new VehicleRuntimeEvent(
                "VEHICLE-PHYSICS-" + trainId + "-" + tick,
                trainId, "VehiclePhysicsUpdated", "",
                now));
        }
        if (after.tractionPowerWatts() > 0) {
            events.add(new VehicleRuntimeEvent(
                "TRACTION-POWER-" + trainId + "-" + tick,
                trainId, "TractionPowerChanged", "",
                now));
        }
        if (after.brakeForceNewtons() > 0) {
            events.add(new VehicleRuntimeEvent(
                "BRAKE-FORCE-" + trainId + "-" + tick,
                trainId, "BrakeForceChanged", "",
                now));
        }
        if (after.regenPowerWatts() > 0) {
            events.add(new VehicleRuntimeEvent(
                "REGEN-POWER-" + trainId + "-" + tick,
                trainId, "RegenerativePowerGenerated", "",
                now));
        }
        if (!java.util.Objects.equals(before.faultCode(), after.faultCode())) {
            events.add(new VehicleRuntimeEvent(
                "FAULT-" + trainId + "-" + tick,
                trainId, "TrainFaultStateChanged",
                "faultCode: " + before.faultCode() + " -> " + after.faultCode(),
                now));
        }
        return events;
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
