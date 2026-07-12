package com.railwaysim.vehicle.onboard;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.config.StoppingControlProperties;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
/** @deprecated LOCAL mode only. Use 9300 VehicleControlQueue for control decisions in EXTERNAL_HTTP mode. */
@Deprecated(forRemoval=true, since="2.0")
public class OnboardTrainSubsystemManager {

    private final SimulationProperties simulationProperties;
    private final LocalOnboardTrainSubsystemClient localClient;
    private final OnboardTrainSubsystemClient externalClient;
    private final OnboardTrainNodeRegistry nodeRegistry;

    @Autowired
    public OnboardTrainSubsystemManager(
        SimulationProperties simulationProperties,
        StoppingControlProperties stoppingProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        RestClient.Builder restClientBuilder
    ) {
        this(
            simulationProperties,
            new LocalOnboardTrainSubsystemClient(
                simulationProperties, infrastructureCatalog, stoppingProperties),
            new HttpOnboardTrainSubsystemClient(simulationProperties, restClientBuilder),
            new OnboardTrainNodeRegistry()
        );
    }

    public OnboardTrainSubsystemManager(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog
    ) {
        this(
            simulationProperties,
            new LocalOnboardTrainSubsystemClient(
                simulationProperties, infrastructureCatalog, new StoppingControlProperties()),
            new HttpOnboardTrainSubsystemClient(simulationProperties, RestClient.builder()),
            new OnboardTrainNodeRegistry()
        );
    }

    OnboardTrainSubsystemManager(
        SimulationProperties simulationProperties,
        LocalOnboardTrainSubsystemClient localClient,
        OnboardTrainSubsystemClient externalClient,
        OnboardTrainNodeRegistry nodeRegistry
    ) {
        this.simulationProperties = simulationProperties;
        this.localClient = localClient;
        this.externalClient = externalClient;
        this.nodeRegistry = nodeRegistry;
    }

    public OnboardTrainRegistration register(String trainId) {
        return switch (mode()) {
            case IN_PROCESS -> registerLocal(trainId);
            case EXTERNAL_HTTP -> registerExternalOrFallback(trainId);
            case DUAL_SHADOW -> registerDualShadow(trainId);
        };
    }

    public OnboardTrainControlOutput control(OnboardTrainControlInput input) {
        return switch (mode()) {
            case IN_PROCESS -> controlLocal(input);
            case EXTERNAL_HTTP -> controlExternalOrFallback(input);
            case DUAL_SHADOW -> controlDualShadow(input);
        };
    }

    public TrainStateReport buildTrainStateReport(
        TrainState train,
        VehiclePhysicsInput input,
        VehiclePhysicsOutput output
    ) {
        return switch (mode()) {
            case IN_PROCESS -> localClient.buildTrainStateReport(train, input, output);
            case EXTERNAL_HTTP -> buildExternalReportOrFallback(train, input, output);
            case DUAL_SHADOW -> localClient.buildTrainStateReport(train, input, output);
        };
    }

    public void remove(String trainId) {
        if (mode() != OnboardTrainSubsystemMode.IN_PROCESS) {
            try {
                externalClient.remove(trainId);
            } catch (RuntimeException ignored) {
                // Removal must not leave the central system stuck with a stale local fallback.
            }
        }
        localClient.remove(trainId);
        nodeRegistry.remove(trainId);
    }

    public void clear() {
        if (mode() != OnboardTrainSubsystemMode.IN_PROCESS) {
            try {
                externalClient.clear();
            } catch (RuntimeException ignored) {
                // Local cleanup remains authoritative for the central simulator.
            }
        }
        localClient.clear();
        nodeRegistry.clear();
    }

    public int registeredCount() {
        return nodeRegistry.count();
    }

    public List<OnboardTrainNodeState> nodeStates() {
        return nodeRegistry.states();
    }

    private OnboardTrainRegistration registerLocal(String trainId) {
        OnboardTrainRegistration registration = localClient.register(trainId);
        nodeRegistry.register(registration, mode().name(), simulationProperties.getOnboardSubsystemLeaseMillis());
        return registration;
    }

    private OnboardTrainRegistration registerExternalOrFallback(String trainId) {
        try {
            OnboardTrainRegistration registration = externalClient.register(trainId);
            nodeRegistry.register(registration, mode().name(), simulationProperties.getOnboardSubsystemLeaseMillis());
            return registration;
        } catch (RuntimeException exception) {
            OnboardTrainRegistration fallback = localClient.register(trainId);
            nodeRegistry.markFallback(
                trainId,
                mode().name(),
                fallback,
                summarize(exception),
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
            return fallback;
        }
    }

    private OnboardTrainRegistration registerDualShadow(String trainId) {
        OnboardTrainRegistration local = localClient.register(trainId);
        nodeRegistry.register(local, mode().name(), simulationProperties.getOnboardSubsystemLeaseMillis());
        try {
            externalClient.register(trainId);
        } catch (RuntimeException exception) {
            nodeRegistry.markFallback(
                trainId,
                mode().name(),
                local,
                "shadow registration failed: " + summarize(exception),
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
        }
        return local;
    }

    private OnboardTrainControlOutput controlLocal(OnboardTrainControlInput input) {
        OnboardTrainControlOutput output = localClient.control(input);
        nodeRegistry.markOnline(
            output.trainId(),
            output.subsystemId(),
            mode().name(),
            "IN_PROCESS_SIMULATED",
            simulationProperties.getOnboardSubsystemLeaseMillis()
        );
        return output;
    }

    private OnboardTrainControlOutput controlExternalOrFallback(OnboardTrainControlInput input) {
        try {
            OnboardTrainControlOutput output = externalClient.control(input);
            nodeRegistry.markOnline(
                output.trainId(),
                output.subsystemId(),
                mode().name(),
                "EXTERNAL_HTTP",
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
            return output;
        } catch (RuntimeException exception) {
            OnboardTrainControlOutput fallback = localClient.control(input);
            nodeRegistry.markFallback(
                fallback.trainId(),
                mode().name(),
                new OnboardTrainRegistration(fallback.trainId(), fallback.subsystemId(), "IN_PROCESS_SIMULATED"),
                summarize(exception),
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
            return fallback;
        }
    }

    private OnboardTrainControlOutput controlDualShadow(OnboardTrainControlInput input) {
        OnboardTrainControlOutput local = localClient.control(input);
        try {
            OnboardTrainControlOutput shadow = externalClient.control(input);
            nodeRegistry.markOnline(
                local.trainId(),
                shadow.subsystemId(),
                mode().name(),
                "DUAL_SHADOW",
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
        } catch (RuntimeException exception) {
            nodeRegistry.markFallback(
                local.trainId(),
                mode().name(),
                new OnboardTrainRegistration(local.trainId(), local.subsystemId(), "IN_PROCESS_SIMULATED"),
                "shadow control failed: " + summarize(exception),
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
        }
        return local;
    }

    private TrainStateReport buildExternalReportOrFallback(
        TrainState train,
        VehiclePhysicsInput input,
        VehiclePhysicsOutput output
    ) {
        try {
            return externalClient.buildTrainStateReport(train, input, output);
        } catch (RuntimeException exception) {
            nodeRegistry.markFallback(
                input.trainId(),
                mode().name(),
                new OnboardTrainRegistration(input.trainId(), "ONBOARD-" + input.trainId(), "IN_PROCESS_SIMULATED"),
                summarize(exception),
                simulationProperties.getOnboardSubsystemLeaseMillis()
            );
            return localClient.buildTrainStateReport(train, input, output);
        }
    }

    private OnboardTrainSubsystemMode mode() {
        return simulationProperties.getOnboardSubsystemMode();
    }

    private String summarize(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return "HTTP " + responseException.getStatusCode().value();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
