package com.railwaysim.power.external;

import com.railwaysim.config.ExternalPowerNetworkProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class HttpExternalPowerNetworkClient implements ExternalPowerNetworkClient {

    private final RestClient restClient;

    public HttpExternalPowerNetworkClient(
        ExternalPowerNetworkProperties properties,
        RestClient.Builder restClientBuilder
    ) {
        this.restClient = restClientBuilder
            .baseUrl(properties.getBaseUrl())
            .requestFactory(requestFactory(properties.getTimeoutMillis()))
            .build();
    }

    @Override
    public void bootstrap(PowerNetworkBootstrapRequest request) {
        restClient.post()
            .uri("/power-network/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public PowerNetworkStateSnapshot currentState() {
        PowerNetworkStateSnapshot snapshot = restClient.get()
            .uri("/power-network/state")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(PowerNetworkStateSnapshot.class);
        if (snapshot == null) {
            throw new IllegalStateException("External power network returned empty state");
        }
        return snapshot;
    }

    @Override
    public PowerNetworkStateSnapshot queryState(PowerNetworkStateQueryRequest request) {
        PowerNetworkStateSnapshot snapshot = restClient.post()
            .uri("/power-network/state/query")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(PowerNetworkStateSnapshot.class);
        if (snapshot == null) {
            throw new IllegalStateException("External power network returned empty queried state");
        }
        return snapshot;
    }

    @Override
    public List<PowerNetworkEventPayload> events() {
        PowerNetworkEventPayload[] events = restClient.get()
            .uri("/power-network/events")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(PowerNetworkEventPayload[].class);
        return events == null ? List.of() : List.of(events);
    }

    @Override
    public PowerNetworkOperationResult operate(PowerNetworkOperationRequest request) {
        PowerNetworkOperationResult result = restClient.post()
            .uri("/power-network/operations")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(PowerNetworkOperationResult.class);
        if (result == null) {
            throw new IllegalStateException("External power network returned empty operation result");
        }
        return result;
    }

    private SimpleClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(timeoutMillis);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
