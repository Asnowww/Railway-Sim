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
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 中央车辆运行时编排器，统一封装本地兼容链路和外部 HTTP 链路。
 * 生产 EXTERNAL_HTTP 模式下，车辆动力学权威固定在9300；9300不可用时中央不得计算替代物理状态。
 */
@Service
public class VehicleRuntimeIntegrationService implements VehiclePowerLoadForwardingOwner {

    private final VehicleRuntimeProperties properties;
    private final ExternalPowerNetworkProperties externalPowerNetworkProperties;
    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final VehicleRuntimeClient client;
    private final AtomicBoolean bootstrapped = new AtomicBoolean();
    private volatile VehicleRuntimeHealth latestHealth = new VehicleRuntimeHealth(
        VehicleRuntimeMode.EXTERNAL_HTTP, "INITIALIZING", Instant.EPOCH, 0L, "INITIALIZING", 0, "Initializing"
    );
    private volatile List<VehicleRuntimeInstanceState> latestInstances = List.of();

    @Autowired
    public VehicleRuntimeIntegrationService(
        VehicleRuntimeProperties properties,
        ExternalPowerNetworkProperties externalPowerNetworkProperties,
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        VehicleRuntimeClient client
    ) {
        this.properties = properties;
        this.externalPowerNetworkProperties = externalPowerNetworkProperties;
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
        this.client = client;
    }

    public VehicleRuntimeStepResult stepFleet(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        try {
            VehicleRuntimeStepResult result = externalStep(
                context, trains, authorities, trackConstraints, dispatchConstraints, powerConstraints
            );
            latestHealth = result.health();
            latestInstances = result.instanceStates();
            return result;
        } catch (RuntimeException exception) {
            latestHealth = fallbackHealth(summarize(exception));
            throw new IllegalStateException(
                "vehicle runtime 9300 unavailable; central physics fallback is disabled: " + summarize(exception),
                exception
            );
        }
    }

    public void register(TrainState train) {
        try {
            bootstrapIfNeeded();
            VehicleRuntimeInstanceState state = client.registerTrain(train);
            latestInstances = mergeInstance(state);
            latestHealth = client.health();
        } catch (RuntimeException exception) {
            // 保留注册失败状态，后续外部 tick 会明确失败；中央不得创建替代车辆物理状态。
            latestHealth = fallbackHealth(summarize(exception));
        }
    }

    public void remove(String trainId) {
        try {
            client.removeTrain(trainId);
            latestInstances = client.instances();
        } catch (RuntimeException exception) {
            latestHealth = fallbackHealth(summarize(exception));
        }
    }

    public void clear() {
        bootstrapped.set(false);
        latestInstances = List.of();
        try {
            client.clear();
            latestHealth = client.health();
        } catch (RuntimeException exception) {
            latestHealth = fallbackHealth(summarize(exception));
        }
    }

    public VehicleRuntimeStatusResponse status() {
        return new VehicleRuntimeStatusResponse(latestHealth, latestInstances);
    }

    public VehicleRuntimeHealth health() {
        return status().health();
    }

    /** 转发司控台 PLC 输入到 9300（仅 EXTERNAL_HTTP 模式使用）。 */
    public Map<String, Object> forwardPlcInput(String trainId, byte[] payload) {
        return client.forwardPlcInput(trainId, payload);
    }

    public void forwardTractionCut(String trainId, byte[] payload) {
        client.forwardTractionCut(trainId, payload);
    }

    @Override
    public boolean ownsPowerLoadForwarding() {
        // 只有外部车辆运行时真实在线时，中央才让出供电负荷写入权，避免启动初期误判。
        return properties.getMode() == VehicleRuntimeMode.EXTERNAL_HTTP
            && "UP".equals(latestHealth.heartbeatStatus())
            && "GOOD".equals(latestHealth.dataQuality());
    }

    @Override
    public boolean isConfiguredPowerLoadForwardingOwner() {
        return properties.getMode() == VehicleRuntimeMode.EXTERNAL_HTTP
            && externalPowerNetworkProperties.getMode() == ExternalPowerNetworkMode.EXTERNAL_HTTP;
    }

    /** In split mode 8080 sends only signal/track/dispatch control; 9300 obtains power from 9200. */
    public boolean usesExternalPowerAuthority() {
        return isConfiguredPowerLoadForwardingOwner();
    }

    private VehicleRuntimeStepResult externalStep(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        bootstrapIfNeeded(trains);
        // 新模式：不发列车状态给 9300，9300 从本地 TrainStateHolder 读取权威状态
        VehicleRuntimeStepResponse response = client.stepFleet(VehicleRuntimeStepRequest.withoutTrains(
            context.tick(),
            context.deltaSeconds(),
            Instant.now(),
            authorities,
            trackConstraints,
            dispatchConstraints,
            powerConstraints,
            context.simulationRunId()
        ));
        List<TrainState> resultTrainStates = response.trainStates() == null ? List.of() : response.trainStates();
        boolean hasAuthoritativeStates = !resultTrainStates.isEmpty();

        List<VehicleRuntimeTrainStep> steps;
        if (hasAuthoritativeStates) {
            // 9300 返回了权威状态快照 → 用 trainStates 构建 step（输出/report 可能不完全匹配，状态以 trainStates 为准）
            Map<String, TrainState> stateByTrain = resultTrainStates.stream()
                .collect(Collectors.toMap(TrainState::id, Function.identity(), (left, right) -> right));
            Map<String, VehiclePhysicsOutput> outputByTrain = response.trainOutputs() == null ? Map.of()
                : response.trainOutputs().stream()
                    .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
            Map<String, TrainStateReport> reportByTrain = response.trainReports() == null ? Map.of()
                : response.trainReports().stream()
                    .collect(Collectors.toMap(TrainStateReport::trainId, Function.identity(), (left, right) -> right));
            steps = resultTrainStates.stream()
                .map(state -> new VehicleRuntimeTrainStep(
                    state.id(),
                    outputByTrain.get(state.id()),
                    reportByTrain.get(state.id())
                ))
                .toList();
        } else {
            // 旧模式兼容（9300 未返回 trainStates）：用 trains 列表构建
            if (response.trainOutputs() == null || response.trainReports() == null
                || response.trainOutputs().size() != trains.size()
                || response.trainReports().size() != trains.size()) {
                throw new IllegalStateException("vehicle runtime returned incomplete fleet result");
            }
            Map<String, VehiclePhysicsOutput> outputByTrain = response.trainOutputs().stream()
                .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
            Map<String, TrainStateReport> reportByTrain = response.trainReports().stream()
                .collect(Collectors.toMap(TrainStateReport::trainId, Function.identity(), (left, right) -> right));
            steps = trains.stream()
                .map(train -> new VehicleRuntimeTrainStep(train.id(), outputByTrain.get(train.id()), reportByTrain.get(train.id())))
                .toList();
            if (steps.stream().anyMatch(step -> step.output() == null || step.report() == null)) {
                throw new IllegalStateException("vehicle runtime result missing train output");
            }
        }
        List<VehicleRuntimeEvent> responseEvents = response.events() == null ? List.of() : response.events();

        VehicleRuntimeHealth health = new VehicleRuntimeHealth(
            properties.getMode(),
            "UP",
            response.sourceTimestamp(),
            0,
            response.dataQuality(),
            response.instanceStates() == null ? 0 : response.instanceStates().size(),
            "OK",
            latestHealth.physicsMode(),
            latestHealth.fmuModelVersion(),
            latestHealth.parameterSetId(),
            context.simulationRunId(),
            context.tick(),
            latestHealth.topologyHash(),
            latestHealth.configHash(),
            latestHealth.stoppingParameterVersion(),
            latestHealth.bootstrapped()
        );
        return new VehicleRuntimeStepResult(steps, health,
            response.instanceStates() == null ? List.of() : response.instanceStates(),
            resultTrainStates, responseEvents);
    }

    private VehicleRuntimeHealth fallbackHealth(String reason) {
        return new VehicleRuntimeHealth(
            properties.getMode(), "FALLBACK", Instant.now(), latestHealth.latencyMillis(),
            "FALLBACK", latestHealth.instanceCount(), reason, latestHealth.physicsMode(),
            latestHealth.fmuModelVersion(), latestHealth.parameterSetId(),
            latestHealth.simulationRunId(), latestHealth.lastAcceptedTick(),
            latestHealth.topologyHash(), latestHealth.configHash(),
            latestHealth.stoppingParameterVersion(), latestHealth.bootstrapped());
    }

    private synchronized void bootstrapIfNeeded() {
        bootstrapIfNeeded(List.of());
    }

    private synchronized void bootstrapIfNeeded(List<TrainState> recoveryStates) {
        if (!properties.isAutoBootstrap()) {
            return;
        }
        boolean remoteRestarted = false;
        if (bootstrapped.get()) {
            latestHealth = client.health();
            if (latestHealth.bootstrapped()) {
                return;
            }
            bootstrapped.set(false);
            remoteRestarted = true;
        }
        double lineLength = infrastructureCatalog.lineData().lineLengthMeters() > 0
            ? infrastructureCatalog.lineData().lineLengthMeters()
            : simulationProperties.getDefaultLineLengthMeters();
        // 中央把供电仿真地址同步给车辆运行时，由车辆侧在外部模式下推送牵引负荷。
        latestHealth = client.bootstrap(new VehicleRuntimeBootstrapRequest(
            lineLength,
            simulationProperties.getDefaultSpeedLimitMetersPerSecond(),
            simulationProperties.getSafetyGapMeters(),
            externalPowerNetworkProperties.getBaseUrl(),
            properties.getMode() == VehicleRuntimeMode.EXTERNAL_HTTP
                && externalPowerNetworkProperties.getMode() != ExternalPowerNetworkMode.LOCAL,
            infrastructureCatalog.lineData().stations().stream()
                .map(station -> new VehicleRuntimeBootstrapRequest.StationTarget(
                    station.id(), station.name(), station.centerMeters(), station.platformIds()))
                .toList()
        ));
        // 只有 bootstrap 成功后才置位；失败保持可重试，避免外部运行时长期使用默认供电配置。
        bootstrapped.set(true);
        if (remoteRestarted && recoveryStates != null) {
            for (TrainState state : recoveryStates) {
                VehicleRuntimeInstanceState recovered = client.registerTrain(state);
                latestInstances = mergeInstance(recovered);
            }
        }
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
