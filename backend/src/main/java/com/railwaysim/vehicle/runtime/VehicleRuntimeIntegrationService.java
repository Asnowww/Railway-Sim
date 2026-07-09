package com.railwaysim.vehicle.runtime;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.config.ExternalPowerNetworkProperties;
import com.railwaysim.config.VehicleRuntimeProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.power.external.ExternalPowerNetworkMode;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsClient;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.onboard.OnboardTrainControlInput;
import com.railwaysim.vehicle.onboard.OnboardTrainSubsystemManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 中央车辆运行时编排器，统一封装本地链路、外部 HTTP 链路和 fallback。
 */
@Service
public class VehicleRuntimeIntegrationService implements VehiclePowerLoadForwardingOwner {

    private final VehicleRuntimeProperties properties;
    private final ExternalPowerNetworkProperties externalPowerNetworkProperties;
    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final VehicleRuntimeClient client;
    private final OnboardTrainSubsystemManager onboardTrainSubsystemManager;
    private final VehiclePhysicsClient vehiclePhysicsClient;
    private final AtomicBoolean bootstrapped = new AtomicBoolean();
    private volatile VehicleRuntimeHealth latestHealth = VehicleRuntimeHealth.local();
    private volatile List<VehicleRuntimeInstanceState> latestInstances = List.of();

    public VehicleRuntimeIntegrationService(
        VehicleRuntimeProperties properties,
        ExternalPowerNetworkProperties externalPowerNetworkProperties,
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        VehicleRuntimeClient client,
        OnboardTrainSubsystemManager onboardTrainSubsystemManager,
        VehiclePhysicsClient vehiclePhysicsClient
    ) {
        this.properties = properties;
        this.externalPowerNetworkProperties = externalPowerNetworkProperties;
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
        this.client = client;
        this.onboardTrainSubsystemManager = onboardTrainSubsystemManager;
        this.vehiclePhysicsClient = vehiclePhysicsClient;
    }

    public VehicleRuntimeStepResult stepFleet(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        return switch (properties.getMode()) {
            case LOCAL -> localStep(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints, null);
            case EXTERNAL_HTTP -> externalOrFallback(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints);
            case DUAL_SHADOW -> shadowStep(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints);
        };
    }

    public void register(TrainState train) {
        if (!usesExternalRuntime()) {
            return;
        }
        try {
            bootstrapIfNeeded();
            VehicleRuntimeInstanceState state = client.registerTrain(train);
            latestInstances = mergeInstance(state);
            latestHealth = client.health();
        } catch (RuntimeException exception) {
            // 注册失败不能阻塞列车上线，后续 tick 会自动走本地 fallback。
            latestHealth = VehicleRuntimeHealth.fallback(properties.getMode(), summarize(exception));
        }
    }

    public void remove(String trainId) {
        if (!usesExternalRuntime()) {
            return;
        }
        try {
            client.removeTrain(trainId);
            latestInstances = client.instances();
        } catch (RuntimeException exception) {
            latestHealth = VehicleRuntimeHealth.fallback(properties.getMode(), summarize(exception));
        }
    }

    public void clear() {
        bootstrapped.set(false);
        latestInstances = List.of();
        if (!usesExternalRuntime()) {
            latestHealth = VehicleRuntimeHealth.local();
            return;
        }
        try {
            client.clear();
            latestHealth = client.health();
        } catch (RuntimeException exception) {
            latestHealth = VehicleRuntimeHealth.fallback(properties.getMode(), summarize(exception));
        }
    }

    public VehicleRuntimeStatusResponse status() {
        if (!usesExternalRuntime()) {
            return new VehicleRuntimeStatusResponse(VehicleRuntimeHealth.local(), List.of());
        }
        return new VehicleRuntimeStatusResponse(latestHealth, latestInstances);
    }

    public VehicleRuntimeHealth health() {
        return status().health();
    }

    @Override
    public boolean ownsPowerLoadForwarding() {
        // 只有外部车辆运行时真实在线时，中央才让出供电负荷写入权，避免启动初期误判。
        return properties.getMode() == VehicleRuntimeMode.EXTERNAL_HTTP
            && latestHealth.mode() == VehicleRuntimeMode.EXTERNAL_HTTP
            && "UP".equals(latestHealth.heartbeatStatus())
            && "GOOD".equals(latestHealth.dataQuality());
    }

    private VehicleRuntimeStepResult externalOrFallback(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        try {
            VehicleRuntimeStepResult result = externalStep(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints);
            latestHealth = result.health();
            latestInstances = result.instanceStates();
            return result;
        } catch (RuntimeException exception) {
            // 外部车辆运行时在热循环中失败时，中央立即降级到本地模型。
            latestHealth = VehicleRuntimeHealth.fallback(properties.getMode(), summarize(exception));
            return localStep(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints, "EXTERNAL_SIM_FALLBACK");
        }
    }

    private VehicleRuntimeStepResult shadowStep(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        VehicleRuntimeStepResult local = localStep(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints, null);
        try {
            VehicleRuntimeStepResult shadow = externalStep(context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints);
            latestHealth = shadow.health();
            latestInstances = shadow.instanceStates();
        } catch (RuntimeException exception) {
            // 影子模式外部失败只影响监控，不改变中央权威车辆状态。
            latestHealth = VehicleRuntimeHealth.fallback(properties.getMode(), summarize(exception));
        }
        return local;
    }

    private VehicleRuntimeStepResult externalStep(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        bootstrapIfNeeded();
        VehicleRuntimeStepResponse response = client.stepFleet(new VehicleRuntimeStepRequest(
            context.tick(),
            context.deltaSeconds(),
            Instant.now(),
            trains,
            authorities,
            trackConstraints,
            dispatchConstraints,
            powerConstraints
        ));
        if (response.trainOutputs() == null || response.trainReports() == null || response.trainOutputs().size() != trains.size() || response.trainReports().size() != trains.size()) {
            throw new IllegalStateException("vehicle runtime returned incomplete fleet result");
        }
        Map<String, VehiclePhysicsOutput> outputByTrain = response.trainOutputs().stream()
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
        Map<String, TrainStateReport> reportByTrain = response.trainReports().stream()
            .collect(Collectors.toMap(TrainStateReport::trainId, Function.identity(), (left, right) -> right));
        List<VehicleRuntimeTrainStep> steps = trains.stream()
            .map(train -> new VehicleRuntimeTrainStep(train.id(), outputByTrain.get(train.id()), reportByTrain.get(train.id())))
            .toList();
        if (steps.stream().anyMatch(step -> step.output() == null || step.report() == null)) {
            throw new IllegalStateException("vehicle runtime result missing train output");
        }
        VehicleRuntimeHealth health = new VehicleRuntimeHealth(
            properties.getMode(),
            "UP",
            response.sourceTimestamp(),
            0,
            response.dataQuality(),
            response.instanceStates() == null ? 0 : response.instanceStates().size(),
            "OK"
        );
        return new VehicleRuntimeStepResult(steps, health, response.instanceStates() == null ? List.of() : response.instanceStates());
    }

    private VehicleRuntimeStepResult localStep(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints,
        String faultCodeOverride
    ) {
        Map<String, MovementAuthority> authorityByTrain = index(authorities, MovementAuthority::trainId);
        Map<String, TrackConstraint> trackByTrain = index(trackConstraints, TrackConstraint::trainId);
        Map<String, DispatchConstraint> dispatchByTrain = index(dispatchConstraints, DispatchConstraint::trainId);
        Map<String, PowerConstraint> powerByTrain = index(powerConstraints, PowerConstraint::trainId);
        List<VehiclePhysicsInput> inputs = trains.stream()
            .map(train -> onboardTrainSubsystemManager.control(new OnboardTrainControlInput(
                train,
                context,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                dispatchByTrain.get(train.id()),
                powerByTrain.get(train.id())
            )).physicsInput())
            .toList();
        Map<String, VehiclePhysicsInput> inputByTrain = index(inputs, VehiclePhysicsInput::trainId);
        Map<String, VehiclePhysicsOutput> outputByTrain = vehiclePhysicsClient.stepFleet(inputs).stream()
            .map(output -> faultCodeOverride == null ? output : withFaultCode(output, faultCodeOverride))
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
        List<VehicleRuntimeTrainStep> steps = trains.stream()
            .map(train -> {
                VehiclePhysicsInput input = inputByTrain.get(train.id());
                VehiclePhysicsOutput output = outputByTrain.get(train.id());
                TrainStateReport report = onboardTrainSubsystemManager.buildTrainStateReport(train, input, output);
                return new VehicleRuntimeTrainStep(train.id(), output, report);
            })
            .toList();
        VehicleRuntimeHealth health = faultCodeOverride == null ? VehicleRuntimeHealth.local() : VehicleRuntimeHealth.fallback(properties.getMode(), faultCodeOverride);
        return new VehicleRuntimeStepResult(steps, health, latestInstances);
    }

    private synchronized void bootstrapIfNeeded() {
        if (!properties.isAutoBootstrap() || bootstrapped.get()) {
            return;
        }
        double lineLength = infrastructureCatalog.lineData().lineLengthMeters() > 0
            ? infrastructureCatalog.lineData().lineLengthMeters()
            : simulationProperties.getDefaultLineLengthMeters();
        // 中央把供电仿真地址同步给车辆运行时，由车辆侧在外部模式下推送牵引负荷。
        client.bootstrap(new VehicleRuntimeBootstrapRequest(
            lineLength,
            simulationProperties.getDefaultSpeedLimitMetersPerSecond(),
            simulationProperties.getSafetyGapMeters(),
            externalPowerNetworkProperties.getBaseUrl(),
            properties.getMode() == VehicleRuntimeMode.EXTERNAL_HTTP
                && externalPowerNetworkProperties.getMode() != ExternalPowerNetworkMode.LOCAL
        ));
        // 只有 bootstrap 成功后才置位；失败保持可重试，避免外部运行时长期使用默认供电配置。
        bootstrapped.set(true);
    }

    private boolean usesExternalRuntime() {
        return properties.getMode() == VehicleRuntimeMode.EXTERNAL_HTTP || properties.getMode() == VehicleRuntimeMode.DUAL_SHADOW;
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> keyFn) {
        return values.stream()
            .collect(Collectors.toMap(keyFn, Function.identity(), (left, right) -> right));
    }

    private List<VehicleRuntimeInstanceState> mergeInstance(VehicleRuntimeInstanceState state) {
        Map<String, VehicleRuntimeInstanceState> merged = latestInstances.stream()
            .collect(Collectors.toMap(VehicleRuntimeInstanceState::trainId, Function.identity(), (left, right) -> right));
        merged.put(state.trainId(), state);
        return List.copyOf(merged.values());
    }

    private VehiclePhysicsOutput withFaultCode(VehiclePhysicsOutput output, String faultCode) {
        return new VehiclePhysicsOutput(
            output.trainId(),
            output.newPositionMeters(),
            output.newSpeedMetersPerSecond(),
            output.accelerationMetersPerSecondSquared(),
            output.tractionForceNewtons(),
            output.brakeForceNewtons(),
            output.regenBrakeForceNewtons(),
            output.tractionPowerWatts(),
            output.railCurrentAmps(),
            output.regenPowerWatts(),
            output.energyConsumedKwh(),
            output.energyRegeneratedKwh(),
            faultCode
        );
    }

    private String summarize(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return "HTTP " + responseException.getStatusCode().value();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
