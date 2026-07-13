package com.railwaysim.vehicle.onboard;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
class HttpOnboardTrainSubsystemClient implements OnboardTrainSubsystemClient {

    private final RestClient restClient;

    HttpOnboardTrainSubsystemClient(
        SimulationProperties simulationProperties,
        RestClient.Builder restClientBuilder
    ) {
        this.restClient = restClientBuilder
            .baseUrl(simulationProperties.getOnboardSubsystemUrl())
            .requestFactory(requestFactory(simulationProperties.getOnboardSubsystemTimeoutMillis()))
            .build();
    }

    @Override
    public OnboardTrainRegistration register(String trainId) {
        OnboardTrainRegistration response = restClient.put()
            .uri("/api/onboard-trains/{trainId}/registration", trainId)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(new RegistrationRequest(trainId))
            .retrieve()
            .body(OnboardTrainRegistration.class);
        if (response == null) {
            throw new IllegalStateException("Onboard train node returned an empty registration");
        }
        return response;
    }

    @Override
    public OnboardTrainControlOutput control(OnboardTrainControlInput input) {
        OnboardTrainControlOutput response = restClient.post()
            .uri("/api/onboard-trains/{trainId}/control", input.train().id())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(input)
            .retrieve()
            .body(OnboardTrainControlOutput.class);
        if (response == null) {
            throw new IllegalStateException("Onboard train node returned an empty control output");
        }
        return response;
    }

    @Override
    public TrainStateReport buildTrainStateReport(
        TrainState train,
        VehiclePhysicsInput input,
        VehiclePhysicsOutput output
    ) {
        TrainStateReport response = restClient.post()
            .uri("/api/onboard-trains/{trainId}/report", input.trainId())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(new OnboardTrainReportRequest(train, input, output))
            .retrieve()
            .body(TrainStateReport.class);
        if (response == null) {
            throw new IllegalStateException("Onboard train node returned an empty state report");
        }
        return response;
    }

    @Override
    public void remove(String trainId) {
        restClient.delete()
            .uri("/api/onboard-trains/{trainId}/registration", trainId)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void clear() {
        restClient.delete()
            .uri("/api/onboard-trains/registrations")
            .retrieve()
            .toBodilessEntity();
    }

    private SimpleClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(timeoutMillis);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private record RegistrationRequest(String trainId) {
    }
}
