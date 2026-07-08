package com.railwaysim.vehicle;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Service
public class FmuVehiclePhysicsAdapter implements VehiclePhysicsClient {

    private final SimpleVehicleDynamicsModel fallbackModel;
    private final SimulationProperties simulationProperties;
    private final SimpleEventBus eventBus;
    private final RestClient restClient;

    public FmuVehiclePhysicsAdapter(
        SimpleVehicleDynamicsModel fallbackModel,
        SimulationProperties simulationProperties,
        SimpleEventBus eventBus,
        RestClient.Builder restClientBuilder
    ) {
        this.fallbackModel = fallbackModel;
        this.simulationProperties = simulationProperties;
        this.eventBus = eventBus;
        this.restClient = restClientBuilder
            .baseUrl(simulationProperties.getFmuServiceUrl())
            .requestFactory(requestFactory(simulationProperties.getFmuServiceTimeoutMillis()))
            .build();
    }

    @Override
    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        if (!simulationProperties.isFmuServiceEnabled()) {
            return fallback(inputs);
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
            return fallback(inputs, "FMU_STEP_FAILED");
        }
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

    private List<VehiclePhysicsOutput> fallback(List<VehiclePhysicsInput> inputs) {
        return inputs.stream().map(fallbackModel::step).toList();
    }

    private List<VehiclePhysicsOutput> fallback(List<VehiclePhysicsInput> inputs, String faultCode) {
        return fallback(inputs).stream()
            .map(output -> new VehiclePhysicsOutput(
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
            ))
            .toList();
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
}
