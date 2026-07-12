package com.railwaysim.power;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.ExternalPowerNetworkProperties;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.PowerConfigLoader;
import com.railwaysim.infrastructure.SpreadsheetLineDataLoader;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.infrastructure.YamlLineDataLoader;
import com.railwaysim.power.external.ExternalPowerNetworkMode;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PowerIntegrationServiceTests {

    @Test
    void externalHttpRefreshPostsSectionLoadsToStateQuery() throws Exception {
        AtomicReference<String> queryBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/power-network/bootstrap", exchange -> {
            send(exchange, 200, "{\"accepted\":true}");
        });
        server.createContext("/power-network/state/query", exchange -> {
            queryBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, externalSnapshot());
        });
        server.start();
        try {
            PowerIntegrationService integrationService = integrationService(server);

            PowerNetworkStateSnapshot snapshot = integrationService.refreshSnapshot(List.of(
                new PowerSectionLoadSnapshot("P01", List.of("TR-001"), 900_000, 0, 1_200)
            ));

            assertThat(queryBody.get()).contains("sectionLoads", "P01", "TR-001", "900000.0");
            assertThat(snapshot.dataQuality()).isEqualTo("GOOD");
            assertThat(snapshot.thirdRailSections().get(0).contactRailVoltage()).isEqualTo(1420.0);
            assertThat(integrationService.health().dataQuality()).isEqualTo("GOOD");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalHttpRefreshFallsBackWhenStateQueryFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/power-network/bootstrap", exchange -> send(exchange, 200, "{\"accepted\":true}"));
        server.createContext("/power-network/state/query", exchange -> send(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();
        try {
            PowerIntegrationService integrationService = integrationService(server);

            PowerNetworkStateSnapshot snapshot = integrationService.refreshSnapshot(List.of(
                new PowerSectionLoadSnapshot("P01", List.of("TR-001"), 900_000, 0, 1_200)
            ));

            assertThat(snapshot.dataQuality()).isEqualTo("FALLBACK");
            assertThat(integrationService.health().dataQuality()).isEqualTo("FALLBACK");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalHttpRefreshPullsStateWhenVehicleRuntimeOwnsPowerLoads() throws Exception {
        AtomicReference<String> queryBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/power-network/bootstrap", exchange -> send(exchange, 200, "{\"accepted\":true}"));
        server.createContext("/power-network/state", exchange -> send(exchange, 200, externalSnapshot()));
        server.createContext("/power-network/state/query", exchange -> {
            queryBody.set("called");
            send(exchange, 200, externalSnapshot());
        });
        server.start();
        try {
            PowerIntegrationService integrationService = integrationService(server, true);

            PowerNetworkStateSnapshot snapshot = integrationService.refreshSnapshot(List.of(
                new PowerSectionLoadSnapshot("P01", List.of("TR-001"), 900_000, 0, 1_200)
            ));

            assertThat(queryBody.get()).isEmpty();
            assertThat(snapshot.dataQuality()).isEqualTo("GOOD");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalHttpRefreshNeverRewritesLoadsWhenExternalVehiclePowerAuthorityIsConfigured() throws Exception {
        AtomicReference<String> queryBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/power-network/bootstrap", exchange -> send(exchange, 200, "{\"accepted\":true}"));
        server.createContext("/power-network/state", exchange -> send(exchange, 200, externalSnapshotWithoutLoads()));
        server.createContext("/power-network/state/query", exchange -> {
            queryBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, externalSnapshot());
        });
        server.start();
        try {
            PowerIntegrationService integrationService = integrationService(server, true);

            PowerNetworkStateSnapshot snapshot = integrationService.refreshSnapshot(List.of(
                new PowerSectionLoadSnapshot("P01", List.of("TR-001"), 900_000, 0, 1_200)
            ));

            assertThat(queryBody.get()).isEmpty();
            assertThat(snapshot.thirdRailSections().get(0).tractionPowerWatts()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private PowerIntegrationService integrationService(HttpServer server) {
        return integrationService(server, false);
    }

    private PowerIntegrationService integrationService(HttpServer server, boolean vehicleRuntimeOwnsLoads) {
        ExternalPowerNetworkProperties properties = new ExternalPowerNetworkProperties();
        properties.setMode(ExternalPowerNetworkMode.EXTERNAL_HTTP);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeoutMillis(1000);
        StaticInfrastructureCatalog catalog = catalog();
        return new PowerIntegrationService(
            properties,
            new PowerTopologyService(catalog),
            () -> vehicleRuntimeOwnsLoads,
            RestClient.builder()
        );
    }

    private StaticInfrastructureCatalog catalog() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataPath("../config/line-demo.yaml");
        properties.setPowerConfigPath("../config/power_third_rail.yaml");
        return new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String externalSnapshot() {
        return """
            {
              "sourceTimestamp": "2026-07-09T00:00:00Z",
              "heartbeatStatus": "UP",
              "dataQuality": "GOOD",
              "bootstrapped": true,
              "substations": [
                {
                  "id": "SS01",
                  "name": "SS01",
                  "supplyMode": "DOUBLE_END",
                  "availability": "AVAILABLE",
                  "devices": [
                    {
                      "id": "SS01-DCB",
                      "name": "DC breaker",
                      "deviceType": "DC_BREAKER",
                      "state": "CLOSED",
                      "available": true,
                      "affectsSectionIds": ["P01"]
                    }
                  ]
                }
              ],
              "thirdRailSections": [
                {
                  "id": "TRS01",
                  "powerSectionId": "P01",
                  "startMeters": 0.0,
                  "endMeters": 2500.0,
                  "energizationState": "ENERGIZED",
                  "feederState": "AVAILABLE",
                  "recommendedSupplyMode": "DOUBLE_END",
                  "contactRailVoltage": 1420.0,
                  "tractionCurrentAmps": 1200.0,
                  "tractionPowerWatts": 900000.0,
                  "regenPowerWatts": 0.0,
                  "absorbedRegenWatts": 0.0,
                  "supportReason": "test external voltage"
                }
              ],
              "isolators": [],
              "strayCurrentMonitors": [],
              "events": []
            }
            """;
    }

    private static String externalSnapshotWithoutLoads() {
        return externalSnapshot()
            .replace("\"tractionCurrentAmps\": 1200.0", "\"tractionCurrentAmps\": 0.0")
            .replace("\"tractionPowerWatts\": 900000.0", "\"tractionPowerWatts\": 0.0");
    }
}
