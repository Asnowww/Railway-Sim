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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Service
public class PowerIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(PowerIntegrationService.class);
    private static final double POWER_SYNC_TOLERANCE_WATTS = 1_000.0;
    private static final double CURRENT_SYNC_TOLERANCE_AMPS = 1.0;

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
     * 按当前写入权同步供电快照：车辆运行时优先推负荷；若独立供电脚本未反映该负荷，中央按同一快照补写。
     */
    public synchronized PowerNetworkStateSnapshot refreshSnapshot(List<PowerSectionLoadSnapshot> loads) {
        if (properties.getMode() == ExternalPowerNetworkMode.LOCAL) {
            latestSnapshot = powerTopologyService.defaultSnapshot();
            health = ExternalPowerNetworkHealth.local();
            return latestSnapshot;
        }
        try {
            ensureExternalBootstrap();
            Instant startedAt = Instant.now();
            if (vehiclePowerLoadForwardingOwner.isConfiguredPowerLoadForwardingOwner()) {
                // 拆分模式下 9300 是唯一负荷写入方，9200 是唯一供电约束计算方；中央只读快照。
                latestSnapshot = externalClient.currentState();
            } else {
                latestSnapshot = externalClient.queryState(new PowerNetworkStateQueryRequest(toExternalLoads(loads)));
            }
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

    /** Bootstrap only establishes topology; it never writes vehicle load. */
    public synchronized void ensureExternalBootstrap() {
        if (properties.getMode() == ExternalPowerNetworkMode.LOCAL || !properties.isAutoBootstrap()) {
            return;
        }
        try {
            if (bootstrapped) {
                latestSnapshot = externalClient.currentState();
                if (latestSnapshot.bootstrapped()) {
                    return;
                }
                bootstrapped = false;
                log.warn("External power network lost bootstrap state; topology will be reapplied");
            }
            var bootstrapRequest = powerTopologyService.buildBootstrapRequest();
            log.info(
                "Bootstrapping external power network: mode={}, baseUrl={}, lineId={}, topologySegments={}, sectionBindings={}",
                properties.getMode(),
                properties.getBaseUrl(),
                bootstrapRequest.lineId(),
                bootstrapRequest.topologySegments().size(),
                bootstrapRequest.sectionBindings().size()
            );
            externalClient.bootstrap(bootstrapRequest);
            bootstrapped = true;
            log.info("External power network bootstrap completed");
        } catch (RuntimeException exception) {
            bootstrapped = false;
            health = ExternalPowerNetworkHealth.fallback(properties.getMode(), summarize(exception));
            throw exception;
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

    private boolean externalLoadsOutOfSync(
        List<PowerSectionLoadSnapshot> loads,
        PowerNetworkStateSnapshot externalSnapshot
    ) {
        if (loads == null || loads.isEmpty()) {
            return false;
        }
        Map<String, PowerNetworkStateSnapshot.ThirdRailSectionSnapshot> externalByPowerSection = externalSnapshot
            .thirdRailSections()
            .stream()
            .collect(Collectors.toMap(
                PowerNetworkStateSnapshot.ThirdRailSectionSnapshot::powerSectionId,
                Function.identity(),
                (left, right) -> left
            ));
        return loads.stream().anyMatch(load -> {
            PowerNetworkStateSnapshot.ThirdRailSectionSnapshot external = externalByPowerSection.get(load.powerSectionId());
            return external == null
                || materiallyDifferent(load.tractionPowerWatts(), external.tractionPowerWatts(), POWER_SYNC_TOLERANCE_WATTS)
                || materiallyDifferent(load.regenPowerWatts(), external.regenPowerWatts(), POWER_SYNC_TOLERANCE_WATTS)
                || materiallyDifferent(load.currentAmps(), external.tractionCurrentAmps(), CURRENT_SYNC_TOLERANCE_AMPS);
        });
    }

    private boolean materiallyDifferent(double expected, double actual, double absoluteTolerance) {
        return Math.abs(expected - actual) > Math.max(absoluteTolerance, Math.abs(expected) * 0.05);
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
