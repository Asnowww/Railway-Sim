package com.railwaysim.power.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.config.ExternalPowerNetworkProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class HttpExternalPowerNetworkClient implements ExternalPowerNetworkClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        String payload = writeJson(toBootstrapPayload(request));
        // 简易 Python HTTP 服务返回 JSON；读取响应体可确保 bootstrap 请求真正完成并暴露空响应问题。
        String response = restClient.post()
            .uri("/power-network/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("External power network returned empty bootstrap response");
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize external power bootstrap payload", exception);
        }
    }

    private Map<String, Object> toBootstrapPayload(PowerNetworkBootstrapRequest request) {
        // 与 Python 简易服务交互时显式展开 JSON，避免嵌套 record 序列化差异造成空拓扑。
        return Map.ofEntries(
            Map.entry("generatedAt", request.generatedAt().toString()),
            Map.entry("lineId", request.lineId()),
            Map.entry("lineName", request.lineName()),
            Map.entry("nominalVoltage", request.nominalVoltage()),
            Map.entry("minimumVoltage", request.minimumVoltage()),
            Map.entry("cutoffVoltage", request.cutoffVoltage()),
            Map.entry("maxTractionCurrentAmps", request.maxTractionCurrentAmps()),
            Map.entry("topologySegments", request.topologySegments().stream()
                .map(segment -> Map.of(
                    "id", segment.id(),
                    "rawSegmentId", segment.rawSegmentId(),
                    "startMeters", segment.startMeters(),
                    "endMeters", segment.endMeters(),
                    "fromNodeId", segment.fromNodeId(),
                    "toNodeId", segment.toNodeId(),
                    "track", segment.track()
                ))
                .toList()),
            Map.entry("sectionBindings", request.sectionBindings().stream()
                .map(binding -> Map.of(
                    "powerSectionId", binding.powerSectionId(),
                    "thirdRailSectionId", binding.thirdRailSectionId(),
                    "substationId", binding.substationId(),
                    "feederId", binding.feederId(),
                    "startMeters", binding.startMeters(),
                    "endMeters", binding.endMeters(),
                    "isolatorIds", binding.isolatorIds()
                ))
                .toList()),
            Map.entry("substations", request.substations().stream()
                .map(substation -> Map.of(
                    "id", substation.id(),
                    "name", substation.name(),
                    "supplyMode", substation.supplyMode(),
                    "startMeters", substation.startMeters(),
                    "endMeters", substation.endMeters(),
                    "devices", substation.devices().stream()
                        .map(device -> Map.of(
                            "id", device.id(),
                            "name", device.name(),
                            "deviceType", device.deviceType(),
                            "defaultState", device.defaultState(),
                            "ratedVoltage", device.ratedVoltage(),
                            "ratedCurrentAmps", device.ratedCurrentAmps(),
                            "affectsSectionIds", device.affectsSectionIds()
                        ))
                        .toList(),
                    "sectionIds", substation.sectionIds()
                ))
                .toList()),
            Map.entry("isolators", request.isolators().stream()
                .map(isolator -> Map.of(
                    "id", isolator.id(),
                    "name", isolator.name(),
                    "thirdRailSectionId", isolator.thirdRailSectionId(),
                    "positionMeters", isolator.positionMeters(),
                    "defaultState", isolator.defaultState()
                ))
                .toList()),
            Map.entry("strayCurrentMonitors", request.strayCurrentMonitors().stream()
                .map(point -> Map.of(
                    "id", point.id(),
                    "name", point.name(),
                    "sectionId", point.sectionId(),
                    "returnCurrentDeviceId", point.returnCurrentDeviceId(),
                    "positionMeters", point.positionMeters(),
                    "normalMinPotentialVolts", point.normalMinPotentialVolts(),
                    "normalMaxPotentialVolts", point.normalMaxPotentialVolts()
                ))
                .toList())
        );
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
