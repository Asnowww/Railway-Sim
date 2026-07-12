package com.railwaysim.vehicle;

import com.railwaysim.config.ExternalSimulatorProperties;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.vehicle.external.ExternalSegmentMapper;
import com.railwaysim.vehicle.external.ExternalSimulatorMode;
import com.railwaysim.vehicle.external.ExternalUdpPacketCodec;
import com.railwaysim.vehicle.external.ExternalUdpVehicleAdapter;
import com.railwaysim.vehicle.external.ExternalVehicleCommandMapper;
import com.railwaysim.vehicle.external.ExternalVehicleSimulationAdapter;
import com.railwaysim.vehicle.external.LocalFallbackVehicleAdapter;
import com.railwaysim.vehicle.external.RtLabApiVehicleAdapter;
import com.railwaysim.vehicle.external.RtLabVariablePathMapper;
import com.railwaysim.vehicle.external.ShadowCompareAdapter;
import com.railwaysim.vehicle.external.StubRtLabClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Service
/** @deprecated LOCAL mode only. EXTERNAL_HTTP mode delegates FMU calls to 9300. */
@Deprecated(forRemoval=true, since="2.0")
public class FmuVehiclePhysicsAdapter implements VehiclePhysicsClient {

    private final SimulationProperties simulationProperties;
    private final ExternalSimulatorProperties externalSimulatorProperties;
    private final SimpleEventBus eventBus;
    private final RestClient restClient;
    private final LocalFallbackVehicleAdapter localFallbackAdapter;
    private final ExternalUdpVehicleAdapter udpVehicleAdapter;
    private final RtLabApiVehicleAdapter rtLabApiVehicleAdapter;
    private final ShadowCompareAdapter shadowCompareAdapter;

    @Autowired
    public FmuVehiclePhysicsAdapter(
        SimpleVehicleDynamicsModel fallbackModel,
        SimulationProperties simulationProperties,
        ExternalSimulatorProperties externalSimulatorProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        SimpleEventBus eventBus,
        RestClient.Builder restClientBuilder
    ) {
        this.simulationProperties = simulationProperties;
        this.externalSimulatorProperties = externalSimulatorProperties;
        this.eventBus = eventBus;
        this.restClient = restClientBuilder
            .baseUrl(simulationProperties.getFmuServiceUrl())
            .requestFactory(requestFactory(simulationProperties.getFmuServiceTimeoutMillis()))
            .build();
        this.localFallbackAdapter = new LocalFallbackVehicleAdapter(fallbackModel);
        ExternalSegmentMapper segmentMapper = new ExternalSegmentMapper(
            infrastructureCatalog == null ? null : infrastructureCatalog.lineData(),
            externalSimulatorProperties.getSegmentMapping()
        );
        ExternalVehicleCommandMapper commandMapper = new ExternalVehicleCommandMapper(
            segmentMapper,
            externalSimulatorProperties.getMaxTrains()
        );
        this.udpVehicleAdapter = new ExternalUdpVehicleAdapter(
            externalSimulatorProperties,
            commandMapper,
            new ExternalUdpPacketCodec(),
            localFallbackAdapter
        );
        this.rtLabApiVehicleAdapter = new RtLabApiVehicleAdapter(
            externalSimulatorProperties,
            commandMapper,
            new RtLabVariablePathMapper(),
            new StubRtLabClient(),
            localFallbackAdapter
        );
        this.shadowCompareAdapter = new ShadowCompareAdapter(
            localFallbackAdapter,
            rtLabApiVehicleAdapter,
            externalSimulatorProperties.getShadow()
        );
    }

    public FmuVehiclePhysicsAdapter(
        SimpleVehicleDynamicsModel fallbackModel,
        SimulationProperties simulationProperties,
        SimpleEventBus eventBus,
        RestClient.Builder restClientBuilder
    ) {
        this(
            fallbackModel,
            simulationProperties,
            new ExternalSimulatorProperties(),
            null,
            eventBus,
            restClientBuilder
        );
    }

    @Override
    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        ExternalSimulatorMode mode = externalSimulatorProperties.getMode();
        if (mode == ExternalSimulatorMode.LOCAL) {
            return stepLocalMode(inputs);
        }

        try {
            return externalAdapterFor(mode).stepFleet(inputs);
        } catch (RuntimeException exception) {
            publishExternalFallback(inputs, mode, exception);
            return localFallbackAdapter.stepFleetWithFault(inputs, "EXTERNAL_SIM_FALLBACK");
        }
    }

    private List<VehiclePhysicsOutput> stepLocalMode(List<VehiclePhysicsInput> inputs) {
        if (!simulationProperties.isFmuServiceEnabled()) {
            return localFallbackAdapter.stepFleet(inputs);
        }
        try {
            return stepFleetWithRemoteFmu(inputs);
        } catch (RuntimeException exception) {
            Instant now = Instant.now();
            inputs.forEach(input -> eventBus.publish(new FmuStepFailedEvent(
                input.trainId(),
                summarize(exception),
                now
            )));
            eventBus.publish(new FmuFallbackActivatedEvent(
                "fleet",
                "FMU service failed; switched to SimpleVehicleDynamicsModel",
                now
            ));
            return localFallbackAdapter.stepFleetWithFault(inputs, "FMU_STEP_FAILED");
        }
    }

    private ExternalVehicleSimulationAdapter externalAdapterFor(ExternalSimulatorMode mode) {
        return switch (mode) {
            case EXTERNAL_UDP -> udpVehicleAdapter;
            case EXTERNAL_RTLAB_API -> rtLabApiVehicleAdapter;
            case DUAL_SHADOW -> shadowCompareAdapter;
            case LOCAL -> localFallbackAdapter;
        };
    }

    private List<VehiclePhysicsOutput> stepFleetWithRemoteFmu(List<VehiclePhysicsInput> inputs) {
        double deltaSeconds = inputs.isEmpty() ? 0 : inputs.get(0).deltaSeconds();
        StepFleetRequest request = new StepFleetRequest(Instant.now(), deltaSeconds, inputs);
        StepFleetResponse response = restClient.post()
            .uri("/step-fleet")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(StepFleetResponse.class);
        if (response == null || response.trainOutputs() == null) {
            throw new IllegalStateException("FMU service returned an empty response");
        }
        return response.trainOutputs();
    }

    private String summarize(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return "FMU service returned HTTP " + responseException.getStatusCode().value();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private SimpleClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(timeoutMillis);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private void publishExternalFallback(
        List<VehiclePhysicsInput> inputs,
        ExternalSimulatorMode mode,
        RuntimeException exception
    ) {
        Instant now = Instant.now();
        inputs.forEach(input -> eventBus.publish(new FmuStepFailedEvent(
            input.trainId(),
            mode + " failed: " + summarize(exception),
            now
        )));
        eventBus.publish(new FmuFallbackActivatedEvent(
            mode.name(),
            "External vehicle simulator failed; switched to SimpleVehicleDynamicsModel",
            now
        ));
    }
}
