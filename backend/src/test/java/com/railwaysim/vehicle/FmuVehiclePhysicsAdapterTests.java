package com.railwaysim.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.ExternalSimulatorProperties;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.vehicle.external.ExternalSimulatorMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class FmuVehiclePhysicsAdapterTests {

    @Test
    void stepFleetUsesRemoteFmuServiceWhenEnabled() throws IOException {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/step-fleet", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            writeJson(exchange, 200, """
                {
                  "trainOutputs": [
                    {
                      "trainId": "TR-001",
                      "newPositionMeters": 123.4,
                      "newSpeedMetersPerSecond": 5.6,
                      "accelerationMetersPerSecondSquared": 0.7,
                      "tractionForceNewtons": 80000.0,
                      "brakeForceNewtons": 0.0,
                      "regenBrakeForceNewtons": 0.0,
                      "mechanicalTractionPowerWatts": 422400.0,
                      "tractionPowerWatts": 480000.0,
                      "railCurrentAmps": 320.0,
                      "mechanicalRegenPowerWatts": 0.0,
                      "regenPowerWatts": 0.0,
                      "energyConsumedKwh": 0.02,
                      "energyRegeneratedKwh": 0.0,
                      "faultCode": "OK",
                      "instanceState": "ACTIVE",
                      "dataQuality": "GOOD",
                      "fmiStatus": "OK"
                    }
                  ]
                }
                """);
        });

        server.start();
        try {
            FmuVehiclePhysicsAdapter adapter = adapterFor(server, true, new SimpleEventBus());
            List<VehiclePhysicsOutput> outputs = adapter.stepFleet(List.of(sampleInput()));

            assertThat(requestedPath).hasValue("/step-fleet");
            assertThat(outputs).hasSize(1);
            assertThat(outputs.get(0).trainId()).isEqualTo("TR-001");
            assertThat(outputs.get(0).newPositionMeters()).isEqualTo(123.4);
            assertThat(outputs.get(0).railCurrentAmps()).isEqualTo(320.0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stepFleetPublishesFailureEventsAndFallsBackWhenRemoteFmuFails() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/step-fleet", exchange -> writeJson(exchange, 500, """
            {"error": "fmu failed"}
            """));
        SimpleEventBus eventBus = new SimpleEventBus();

        server.start();
        try {
            FmuVehiclePhysicsAdapter adapter = adapterFor(server, true, eventBus);
            List<VehiclePhysicsOutput> outputs = adapter.stepFleet(List.of(sampleInput()));

            assertThat(outputs).hasSize(1);
            assertThat(outputs.get(0).trainId()).isEqualTo("TR-001");
            assertThat(outputs.get(0).faultCode()).isEqualTo("FMU_STEP_FAILED");
            assertThat(eventBus.drain())
                .anySatisfy(event -> assertThat(event).isInstanceOf(FmuStepFailedEvent.class))
                .anySatisfy(event -> assertThat(event).isInstanceOf(FmuFallbackActivatedEvent.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalUdpModeFallsBackToLocalModelWhenPacketExchangeFails() {
        SimulationProperties properties = new SimulationProperties();
        ExternalSimulatorProperties externalProperties = new ExternalSimulatorProperties();
        externalProperties.setMode(ExternalSimulatorMode.EXTERNAL_UDP);
        externalProperties.getUdp().setModelHost("127.0.0.1");
        externalProperties.getUdp().setModelPort(9);
        externalProperties.getUdp().setPlatformHost("127.0.0.1");
        externalProperties.getUdp().setPlatformPort(0);
        externalProperties.getUdp().setTimeoutMillis(5);
        SimpleEventBus eventBus = new SimpleEventBus();
        FmuVehiclePhysicsAdapter adapter = new FmuVehiclePhysicsAdapter(
            new SimpleVehicleDynamicsModel(),
            properties,
            externalProperties,
            null,
            eventBus,
            RestClient.builder()
        );

        List<VehiclePhysicsOutput> outputs = adapter.stepFleet(List.of(sampleInput()));

        assertThat(outputs).hasSize(1);
        assertThat(outputs.get(0).faultCode()).isEqualTo("EXTERNAL_SIM_FALLBACK");
        assertThat(outputs.get(0).newPositionMeters()).isGreaterThan(100);
        assertThat(eventBus.drain())
            .anySatisfy(event -> assertThat(event).isInstanceOf(FmuStepFailedEvent.class))
            .anySatisfy(event -> assertThat(event).isInstanceOf(FmuFallbackActivatedEvent.class));
    }

    @Test
    void dualShadowModeKeepsLocalOutputAsAuthoritativeWithRtLabStub() {
        SimulationProperties properties = new SimulationProperties();
        ExternalSimulatorProperties externalProperties = new ExternalSimulatorProperties();
        externalProperties.setMode(ExternalSimulatorMode.DUAL_SHADOW);
        FmuVehiclePhysicsAdapter adapter = new FmuVehiclePhysicsAdapter(
            new SimpleVehicleDynamicsModel(),
            properties,
            externalProperties,
            null,
            new SimpleEventBus(),
            RestClient.builder()
        );

        VehiclePhysicsOutput local = new SimpleVehicleDynamicsModel().step(sampleInput());
        VehiclePhysicsOutput shadowed = adapter.stepFleet(List.of(sampleInput())).get(0);

        assertThat(shadowed.newPositionMeters()).isEqualTo(local.newPositionMeters());
        assertThat(shadowed.newSpeedMetersPerSecond()).isEqualTo(local.newSpeedMetersPerSecond());
        assertThat(shadowed.faultCode()).isEqualTo("OK");
    }

    private FmuVehiclePhysicsAdapter adapterFor(HttpServer server, boolean enabled, SimpleEventBus eventBus) {
        SimulationProperties properties = new SimulationProperties();
        properties.setFmuServiceEnabled(enabled);
        properties.setFmuServiceUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setFmuServiceTimeoutMillis(500);
        return new FmuVehiclePhysicsAdapter(
            new SimpleVehicleDynamicsModel(),
            properties,
            eventBus,
            RestClient.builder()
        );
    }

    private VehiclePhysicsInput sampleInput() {
        return new VehiclePhysicsInput(
            "TR-001",
            100.0,
            0.0,
            220_000.0,
            0.7,
            0.0,
            false,
            20.0,
            900.0,
            0.0,
            1_000.0,
            1_500.0,
            3_000_000.0,
            true,
            0.9,
            0.0,
            0.0,
            0.1,
            "ACCELERATING",
            "SPEED_MARGIN_AVAILABLE",
            1_000.0,
            0.0
        );
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
