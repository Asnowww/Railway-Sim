package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.model.DispatchConstraintSnapshot;
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

    private final VehicleRuntimeProperties properties;
    private final VehicleParameters vehicleParameters;
    private final PowerNetworkLoadClient powerNetworkLoadClient;
    private final CentralTrainRegistrationClient centralTrainRegistrationClient;
    private final Map<String, VehicleRuntimeInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, PowerConstraintSnapshot> authoritativePowerByTrain = new ConcurrentHashMap<>();
    private final List<VehicleRuntimeEvent> events = new ArrayList<>();
    private volatile VehicleRuntimeHealth latestHealth;

    public VehicleRuntimeManager(
        VehicleRuntimeProperties properties,
        VehicleParameters vehicleParameters,
        PowerNetworkLoadClient powerNetworkLoadClient,
        CentralTrainRegistrationClient centralTrainRegistrationClient
    ) {
        this.properties = properties;
        this.vehicleParameters = vehicleParameters;
        this.powerNetworkLoadClient = powerNetworkLoadClient;
        this.centralTrainRegistrationClient = centralTrainRegistrationClient;
        this.latestHealth = new VehicleRuntimeHealth("EXTERNAL_HTTP", "UP", Instant.now(), 0, "GOOD", 0, "READY");
    }

    public VehicleRuntimeHealth health() {
        return new VehicleRuntimeHealth(
            "EXTERNAL_HTTP",
            latestHealth.heartbeatStatus(),
            Instant.now(),
            latestHealth.latencyMillis(),
            latestHealth.dataQuality(),
            instances.size(),
            latestHealth.reason()
        );
    }

    public VehicleParameterMetadata parameterMetadata() {
        return new VehicleParameterMetadata(
            vehicleParameters.parameterSetId(),
            vehicleParameters.sourcePath().toString(),
            vehicleParameters.trainType(),
            vehicleParameters.emptyMassKg(),
            vehicleParameters.maxLoadMassKg(),
            vehicleParameters.traction().maxPowerWatts()
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
        latestHealth = new VehicleRuntimeHealth("EXTERNAL_HTTP", "UP", Instant.now(), 0, "GOOD", instances.size(), "BOOTSTRAPPED");
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
                centralTrainRegistrationClient.register(CentralTrainRegistrationRequest.from(request));
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
        latestHealth = new VehicleRuntimeHealth("EXTERNAL_HTTP", "UP", Instant.now(), 0, dataQuality, instances.size(), reason);
        return new VehicleRuntimeLaunchResponse(trainId, instance.state(), registrationStatus, reason);
    }

    public void remove(String trainId) {
        if (trainId != null && instances.remove(trainId) != null) {
            recordEvent(trainId, "REMOVE", "vehicle runtime instance removed");
        }
    }

    public void clear() {
        instances.clear();
        recordEvent("runtime", "CLEAR", "all vehicle runtime instances cleared");
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
        Instant startedAt = Instant.now();
        List<TrainStateSnapshot> trains = request.trains() == null ? List.of() : request.trains();
        Map<String, MovementAuthoritySnapshot> authorityByTrain = index(request.movementAuthorities(), MovementAuthoritySnapshot::trainId);
        Map<String, TrackConstraintSnapshot> trackByTrain = index(request.trackConstraints(), TrackConstraintSnapshot::trainId);
        Map<String, DispatchConstraintSnapshot> dispatchByTrain = index(request.dispatchConstraints(), DispatchConstraintSnapshot::trainId);
        Map<String, PowerConstraintSnapshot> powerByTrain = powerConstraintsForStep(request, trains);
        List<VehiclePhysicsOutputDto> outputs = new ArrayList<>();
        List<TrainStateReportDto> reports = new ArrayList<>();
        List<VehicleRuntimeInstanceState> states = new ArrayList<>();

        for (TrainStateSnapshot train : trains) {
            VehicleRuntimeInstance instance = instances.computeIfAbsent(
                train.id(),
                id -> new VehicleRuntimeInstance(id, properties, vehicleParameters)
            );
            VehicleRuntimeInstance.StepResult result = instance.step(
                request.tick(),
                request.deltaSeconds(),
                train,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                dispatchByTrain.get(train.id()),
                powerByTrain.get(train.id())
            );
            result.output().ifPresent(outputs::add);
            result.report().ifPresent(reports::add);
            states.add(result.state());
        }

        String dataQuality = outputs.size() == trains.size() && reports.size() == trains.size() ? "GOOD" : "DEGRADED";
        String reason = dataQuality.equals("GOOD") ? "OK" : "PARTIAL_STEP";
        try {
            // 车辆状态变化先汇总为同分区负荷，再由权威供电仿真返回下一控制周期的约束。
            List<PowerConstraintSnapshot> nextConstraints = powerNetworkLoadClient.stepPowerNetwork(
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
        latestHealth = new VehicleRuntimeHealth(
            "EXTERNAL_HTTP",
            "UP",
            Instant.now(),
            latency,
            dataQuality,
            instances.size(),
            reason
        );
        return new VehicleRuntimeStepResponse(request.tick(), Instant.now(), dataQuality, outputs, reports, states);
    }

    public synchronized List<VehicleRuntimeEvent> events() {
        return List.copyOf(events);
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
