package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.PowerNetworkSectionLoadRequest;
import com.railwaysim.vehicleruntime.model.PowerNetworkConstraintQueryRequest;
import com.railwaysim.vehicleruntime.model.PowerNetworkStepRequest;
import com.railwaysim.vehicleruntime.model.PowerNetworkStepResponse;
import com.railwaysim.vehicleruntime.model.PowerNetworkTrainPosition;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 车辆运行时向供电仿真推送负荷，形成车辆仿真 -> 供电仿真网络链路。
 */
@Component
public class PowerNetworkLoadClient {

    private final VehicleRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;
    private volatile RestClient restClient;
    private volatile String restClientBaseUrl = "";
    private volatile long restClientTimeoutMillis = -1;

    public PowerNetworkLoadClient(VehicleRuntimeProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    public boolean enabled() {
        return properties.isForwardPowerLoads();
    }

    /** Read the authoritative constraint before the vehicle control queue runs. */
    public List<PowerConstraintSnapshot> queryConstraints(List<PowerNetworkTrainPosition> trainPositions) {
        if (!enabled()) {
            return List.of();
        }
        PowerNetworkStepResponse response = client()
            .post()
            .uri("/power-network/constraints/query-compact")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(new PowerNetworkConstraintQueryRequest(trainPositions))
            .retrieve()
            .body(PowerNetworkStepResponse.class);
        return response == null ? List.of() : response.powerConstraints();
    }

    /**
     * The vehicle runtime is the sole load writer.  The response supplies the
     * next control-cycle constraints after all trains in the fleet were merged.
     */
    public List<PowerConstraintSnapshot> stepPowerNetwork(
        String simulationRunId,
        long tick,
        double simulationTimeSeconds,
        double stepSizeSeconds,
        List<PowerNetworkSectionLoadRequest> loads,
        List<PowerNetworkTrainPosition> trainPositions
    ) {
        if (!enabled()) {
            return List.of();
        }
        PowerNetworkStepResponse response = client()
            .post()
            .uri("/power-network/step-compact")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(new PowerNetworkStepRequest(simulationRunId, tick, simulationTimeSeconds, stepSizeSeconds, loads, trainPositions))
            .retrieve()
            .body(PowerNetworkStepResponse.class);
        return response == null ? List.of() : response.powerConstraints();
    }

    private RestClient client() {
        String baseUrl = properties.getPowerNetworkBaseUrl();
        long timeoutMillis = properties.getPowerNetworkTimeoutMillis();
        RestClient current = restClient;
        if (current != null
            && baseUrl.equals(restClientBaseUrl)
            && timeoutMillis == restClientTimeoutMillis) {
            return current;
        }
        synchronized (this) {
            if (restClient == null
                || !baseUrl.equals(restClientBaseUrl)
                || timeoutMillis != restClientTimeoutMillis) {
                restClient = restClientBuilder
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory(timeoutMillis))
                    .build();
                restClientBaseUrl = baseUrl;
                restClientTimeoutMillis = timeoutMillis;
            }
            return restClient;
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMillis));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
