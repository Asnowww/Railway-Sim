package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.CentralTrainRegistrationRequest;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 车辆运行时主动向中央系统注册列车镜像，避免启动流程必须由中央反向拉起。
 */
@Component
public class CentralTrainRegistrationClient {

    private final VehicleRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;

    public CentralTrainRegistrationClient(VehicleRuntimeProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * 将已启动的车辆仿真实例登记到中央；失败由上层转为 DEGRADED 启动结果。
     */
    public void register(CentralTrainRegistrationRequest request) {
        restClientBuilder
            .baseUrl(properties.getCentralBaseUrl())
            .requestFactory(requestFactory(properties.getCentralTimeoutMillis()))
            .build()
            .post()
            .uri("/api/trains/runtime-registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
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
