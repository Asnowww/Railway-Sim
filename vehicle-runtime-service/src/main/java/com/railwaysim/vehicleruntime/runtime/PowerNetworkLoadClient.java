package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.PowerNetworkSectionLoadRequest;
import com.railwaysim.vehicleruntime.model.PowerNetworkStateQueryRequest;
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

    public PowerNetworkLoadClient(VehicleRuntimeProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    public boolean enabled() {
        return properties.isForwardPowerLoads();
    }

    /**
     * 把本 tick 聚合出的分区负荷写入供电仿真；失败由调用方转为 DEGRADED。
     */
    public void pushLoads(List<PowerNetworkSectionLoadRequest> loads) {
        if (!enabled() || loads == null || loads.isEmpty()) {
            return;
        }
        restClientBuilder
            .baseUrl(properties.getPowerNetworkBaseUrl())
            .requestFactory(requestFactory(properties.getPowerNetworkTimeoutMillis()))
            .build()
            .post()
            .uri("/power-network/state/query")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(new PowerNetworkStateQueryRequest(loads))
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
}
