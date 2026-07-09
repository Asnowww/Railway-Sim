package com.railwaysim.localnet.powerpoints;

import com.railwaysim.config.LocalNetProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class HttpSimPowerPointDriver implements PowerPointDriver {

    private final RestClient restClient;

    public HttpSimPowerPointDriver(LocalNetProperties.PowerPoints properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
            .baseUrl(properties.getHttpBaseUrl())
            .requestFactory(requestFactory(properties.getTimeoutMillis()))
            .build();
    }

    @Override
    public List<PowerPointValue> snapshot(List<PowerPointDefinition> definitions) {
        PowerPointValue[] values = restClient.get()
            .uri("/power-points/state")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(PowerPointValue[].class);
        return values == null ? List.of() : Arrays.asList(values);
    }

    @Override
    public PowerPointValue write(PowerPointDefinition definition, String value) {
        PowerPointValue result = restClient.post()
            .uri("/power-points/{pointId}", definition.pointId())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(Map.of("value", value == null ? definition.desiredState() : value))
            .retrieve()
            .body(PowerPointValue.class);
        return result == null
            ? new PowerPointValue(definition.pointId(), value, "UNKNOWN", null)
            : result;
    }

    private SimpleClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(timeoutMillis);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
