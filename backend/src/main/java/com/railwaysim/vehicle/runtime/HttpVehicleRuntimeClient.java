package com.railwaysim.vehicle.runtime;

import com.railwaysim.config.VehicleRuntimeProperties;
import com.railwaysim.train.TrainState;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 中央到外部车辆运行时的 HTTP 客户端，只供后端热循环和监控接口使用。
 */
@Component
public class HttpVehicleRuntimeClient implements VehicleRuntimeClient {

    private final RestClient restClient;

    public HttpVehicleRuntimeClient(VehicleRuntimeProperties properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
            .baseUrl(properties.getBaseUrl())
            .requestFactory(requestFactory(properties.getTimeoutMillis()))
            .build();
    }

    @Override
    public VehicleRuntimeHealth health() {
        VehicleRuntimeHealth health = restClient.get()
            .uri("/vehicle-runtime/health")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(VehicleRuntimeHealth.class);
        return require(health, "vehicle runtime health is empty");
    }

    @Override
    public VehicleRuntimeHealth bootstrap(VehicleRuntimeBootstrapRequest request) {
        VehicleRuntimeHealth health = restClient.post()
            .uri("/vehicle-runtime/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(VehicleRuntimeHealth.class);
        return require(health, "vehicle runtime bootstrap response is empty");
    }

    @Override
    public VehicleRuntimeInstanceState registerTrain(TrainState train) {
        VehicleRuntimeInstanceState state = restClient.put()
            .uri("/vehicle-runtime/trains/{trainId}", train.id())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(train)
            .retrieve()
            .body(VehicleRuntimeInstanceState.class);
        return require(state, "vehicle runtime registration response is empty");
    }

    @Override
    public void removeTrain(String trainId) {
        restClient.delete()
            .uri("/vehicle-runtime/trains/{trainId}", trainId)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void clear() {
        restClient.delete()
            .uri("/vehicle-runtime/trains")
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public List<VehicleRuntimeInstanceState> instances() {
        VehicleRuntimeInstanceState[] states = restClient.get()
            .uri("/vehicle-runtime/instances")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(VehicleRuntimeInstanceState[].class);
        return states == null ? List.of() : Arrays.asList(states);
    }

    @Override
    public VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request) {
        VehicleRuntimeStepResponse response = restClient.post()
            .uri("/vehicle-runtime/step-fleet")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(VehicleRuntimeStepResponse.class);
        return require(response, "vehicle runtime step response is empty");
    }

    @Override
    public void forwardPlcInput(String trainId, byte[] payload) {
        restClient.post()
            .uri("/api/vehicle/driver-cabs/{trainId}/plc-input", trainId)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(payload)
            .retrieve()
            .toBodilessEntity();
    }

    private SimpleClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMillis));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
