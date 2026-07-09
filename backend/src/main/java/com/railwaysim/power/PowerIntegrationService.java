package com.railwaysim.power;

import com.railwaysim.config.ExternalPowerNetworkProperties;
import com.railwaysim.power.external.ExternalPowerNetworkClient;
import com.railwaysim.power.external.ExternalPowerNetworkHealth;
import com.railwaysim.power.external.ExternalPowerNetworkMode;
import com.railwaysim.power.external.HttpExternalPowerNetworkClient;
import com.railwaysim.power.external.PowerNetworkEventPayload;
import com.railwaysim.power.external.PowerNetworkOperationRequest;
import com.railwaysim.power.external.PowerNetworkOperationResult;
import com.railwaysim.power.external.PowerNetworkSectionLoadRequest;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import com.railwaysim.power.external.PowerNetworkStateQueryRequest;
import com.railwaysim.vehicle.runtime.VehiclePowerLoadForwardingOwner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Service
public class PowerIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(PowerIntegrationService.class);

    private final ExternalPowerNetworkProperties properties;
    private final PowerTopologyService powerTopologyService;
    private final VehiclePowerLoadForwardingOwner vehiclePowerLoadForwardingOwner;
    private final ExternalPowerNetworkClient externalClient;
    private volatile PowerNetworkStateSnapshot latestSnapshot;
    private volatile ExternalPowerNetworkHealth health;
    private volatile boolean bootstrapped;

    public PowerIntegrationService(
        ExternalPowerNetworkProperties properties,
        PowerTopologyService powerTopologyService,
        VehiclePowerLoadForwardingOwner vehiclePowerLoadForwardingOwner,
        RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.powerTopologyService = powerTopologyService;
        this.vehiclePowerLoadForwardingOwner = vehiclePowerLoadForwardingOwner;
        this.externalClient = new HttpExternalPowerNetworkClient(properties, restClientBuilder);
        this.latestSnapshot = powerTopologyService.defaultSnapshot();
        this.health = ExternalPowerNetworkHealth.local();
        // 外部模式可能由启动参数后绑定，bootstrap 只在真实下发成功后才标记完成。
        this.bootstrapped = false;
    }

    public synchronized PowerNetworkStateSnapshot refreshSnapshot() {
        return refreshSnapshot(List.of());
    }

    /**
     * 按当前写入权同步供电快照：车辆运行时负责推负荷时只读状态，否则由中央提交负荷。
     */
    public synchronized PowerNetworkStateSnapshot refreshSnapshot(List<PowerSectionLoadSnapshot> loads) {
        if (properties.getMode() == ExternalPowerNetworkMode.LOCAL) {
            latestSnapshot = powerTopologyService.defaultSnapshot();
            health = ExternalPowerNetworkHealth.local();
            return latestSnapshot;
        }
        try {
            if (properties.isAutoBootstrap() && !bootstrapped) {
                // 首次外部刷新必须先下发中央生成的虚拟电网拓扑，否则外部只会返回空遥测。
                var bootstrapRequest = powerTopologyService.buildBootstrapRequest();
                log.info(
                    "Bootstrapping external power network: mode={}, baseUrl={}, lineId={}, topologySegments={}, sectionBindings={}, loads={}",
                    properties.getMode(),
                    properties.getBaseUrl(),
                    bootstrapRequest.lineId(),
                    bootstrapRequest.topologySegments().size(),
                    bootstrapRequest.sectionBindings().size(),
                    loads == null ? 0 : loads.size()
                );
                externalClient.bootstrap(bootstrapRequest);
                bootstrapped = true;
                log.info("External power network bootstrap completed");
            }
            Instant startedAt = Instant.now();
            // 外部车辆运行时已把牵引/再生负荷推到供电仿真时，中央只拉取供电状态，避免重复注入负荷。
            latestSnapshot = vehiclePowerLoadForwardingOwner.ownsPowerLoadForwarding()
                ? externalClient.currentState()
                : externalClient.queryState(new PowerNetworkStateQueryRequest(toExternalLoads(loads)));
            health = new ExternalPowerNetworkHealth(
                properties.getMode(),
                latestSnapshot.heartbeatStatus(),
                latestSnapshot.sourceTimestamp(),
                Duration.between(startedAt, Instant.now()).toMillis(),
                latestSnapshot.dataQuality()
            );
            return latestSnapshot;
        } catch (RuntimeException exception) {
            health = ExternalPowerNetworkHealth.fallback(properties.getMode(), summarize(exception));
            latestSnapshot = powerTopologyService.defaultSnapshot("FALLBACK", "FALLBACK");
            return latestSnapshot;
        }
    }

    private List<PowerNetworkSectionLoadRequest> toExternalLoads(List<PowerSectionLoadSnapshot> loads) {
        return loads == null
            ? List.of()
            : loads.stream()
                .map(load -> new PowerNetworkSectionLoadRequest(
                    load.powerSectionId(),
                    load.trainIds(),
                    load.tractionPowerWatts(),
                    load.regenPowerWatts(),
                    load.currentAmps()
                ))
                .toList();
    }

    public synchronized PowerNetworkOperationResult operate(PowerNetworkOperationRequest request) {
        if (properties.getMode() == ExternalPowerNetworkMode.LOCAL) {
            return new PowerNetworkOperationResult(
                true,
                false,
                request.targetId(),
                "LOCAL_MODE",
                "external power network is disabled",
                request.traceId(),
                Instant.now()
            );
        }
        try {
            PowerNetworkOperationResult result = externalClient.operate(request);
            refreshSnapshot();
            return result;
        } catch (RuntimeException exception) {
            health = ExternalPowerNetworkHealth.fallback(properties.getMode(), summarize(exception));
            return new PowerNetworkOperationResult(
                false,
                false,
                request.targetId(),
                "FAILED",
                summarize(exception),
                request.traceId(),
                Instant.now()
            );
        }
    }

    public synchronized List<PowerNetworkEventPayload> events() {
        if (properties.getMode() == ExternalPowerNetworkMode.LOCAL) {
            return latestSnapshot.events();
        }
        try {
            return externalClient.events();
        } catch (RuntimeException exception) {
            health = ExternalPowerNetworkHealth.fallback(properties.getMode(), summarize(exception));
            return List.of();
        }
    }

    public PowerNetworkStateSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public ExternalPowerNetworkHealth health() {
        return health;
    }

    public ExternalPowerNetworkMode mode() {
        return properties.getMode();
    }

    private String summarize(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return "HTTP " + responseException.getStatusCode().value();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
