package com.railwaysim.vehicle.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.config.VehicleRuntimeProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.OperationalPowerData.PowerSectionDefinition;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.vehicle.SimpleVehicleDynamicsModel;
import com.railwaysim.vehicle.onboard.OnboardTrainSubsystemManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class VehicleRuntimeIntegrationServiceTests {

    @Test
    void externalHttpModeUsesRemoteRuntimeOutputAndSendsConstraints() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vehicle-runtime/bootstrap", exchange -> writeJson(exchange, 200, healthJson("GOOD")));
        server.createContext("/vehicle-runtime/step-fleet", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, stepResponseJson("OK", "GOOD", 321.0));
        });
        server.start();
        try {
            VehicleRuntimeIntegrationService service = service(server, VehicleRuntimeMode.EXTERNAL_HTTP);

            VehicleRuntimeStepResult result = service.stepFleet(tick(1), List.of(train()), authority(), track(), power());

            assertThat(requestBody.get()).contains("\"powerConstraints\"");
            assertThat(requestBody.get()).contains("\"movementAuthorities\"");
            assertThat(result.outputs()).singleElement()
                .satisfies(output -> assertThat(output.newPositionMeters()).isEqualTo(321.0));
            assertThat(result.health().dataQuality()).isEqualTo("GOOD");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalHttpFailureFallsBackToLocalRuntimeAndMarksDataQuality() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vehicle-runtime/bootstrap", exchange -> writeJson(exchange, 200, healthJson("GOOD")));
        server.createContext("/vehicle-runtime/step-fleet", exchange -> writeJson(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();
        try {
            VehicleRuntimeIntegrationService service = service(server, VehicleRuntimeMode.EXTERNAL_HTTP);

            VehicleRuntimeStepResult result = service.stepFleet(tick(1), List.of(train()), authority(), track(), power());

            assertThat(result.outputs()).singleElement()
                .satisfies(output -> assertThat(output.faultCode()).isEqualTo("EXTERNAL_SIM_FALLBACK"));
            assertThat(result.trainSteps()).singleElement()
                .satisfies(step -> assertThat(step.report().dataQuality()).isEqualTo("FALLBACK"));
            assertThat(result.health().dataQuality()).isEqualTo("FALLBACK");
        } finally {
            server.stop(0);
        }
    }

    private VehicleRuntimeIntegrationService service(HttpServer server, VehicleRuntimeMode mode) {
        SimulationProperties simulationProperties = new SimulationProperties();
        VehicleRuntimeProperties properties = new VehicleRuntimeProperties();
        properties.setMode(mode);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeoutMillis(300);
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData(), powerData());
        OnboardTrainSubsystemManager onboard = new OnboardTrainSubsystemManager(simulationProperties, catalog);
        return new VehicleRuntimeIntegrationService(
            properties,
            simulationProperties,
            catalog,
            new HttpVehicleRuntimeClient(properties, RestClient.builder()),
            onboard,
            inputs -> inputs.stream().map(new SimpleVehicleDynamicsModel()::step).toList()
        );
    }

    private TickContext tick(long tick) {
        return new TickContext(tick, 200, 0.2, Instant.parse("2026-07-09T00:00:00Z"));
    }

    private com.railwaysim.train.TrainState train() {
        return new TrainEntity("TR-201", "demo-line", 100, 120, 0.42).state();
    }

    private List<MovementAuthority> authority() {
        return List.of(new MovementAuthority("TR-201", 2_000, 22.2, "NORMAL"));
    }

    private List<TrackConstraint> track() {
        return List.of(new TrackConstraint("TR-201", "SEG-1", 22.2, 0, 1_000, 1_000_000));
    }

    private List<PowerConstraint> power() {
        return List.of(new PowerConstraint("TR-201", "P01", 1500, 3_200_000, true));
    }

    private OperationalLineData lineData() {
        return new OperationalLineData(
            "demo-line",
            "Demo Line",
            List.of(),
            List.of(new OperationalLineData.TrackSegmentDefinition("SEG-1", 1, 0, 5_000, 5_000, 22.2, 0, 0, 0, 0, List.of(), List.of(), "A", "B", "main")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private OperationalPowerData powerData() {
        return new OperationalPowerData(
            1500,
            1000,
            0,
            2400,
            3000,
            0.02,
            true,
            "DISSIPATE",
            List.of(new PowerSectionDefinition("P01", "Power 01", "SS01", "F01", 0, 5_000, 1500, true, "CLOSED", "CLOSED", "NORMAL", "NONE", "UNLOCKED", 0.00005)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private String healthJson(String dataQuality) {
        return """
            {
              "mode": "EXTERNAL_HTTP",
              "heartbeatStatus": "UP",
              "sourceTimestamp": "2026-07-09T00:00:00Z",
              "latencyMillis": 1,
              "dataQuality": "%s",
              "instanceCount": 1,
              "reason": "OK"
            }
            """.formatted(dataQuality);
    }

    private String stepResponseJson(String faultCode, String dataQuality, double position) {
        return """
            {
              "tick": 1,
              "sourceTimestamp": "2026-07-09T00:00:00Z",
              "dataQuality": "%s",
              "trainOutputs": [
                {
                  "trainId": "TR-201",
                  "newPositionMeters": %.1f,
                  "newSpeedMetersPerSecond": 3.0,
                  "accelerationMetersPerSecondSquared": 0.5,
                  "tractionForceNewtons": 100000.0,
                  "brakeForceNewtons": 0.0,
                  "regenBrakeForceNewtons": 0.0,
                  "tractionPowerWatts": 500000.0,
                  "railCurrentAmps": 333.0,
                  "regenPowerWatts": 0.0,
                  "energyConsumedKwh": 0.1,
                  "energyRegeneratedKwh": 0.0,
                  "faultCode": "%s"
                }
              ],
              "trainReports": [
                {
                  "trainId": "TR-201",
                  "operationMode": "ATO",
                  "doorClosed": true,
                  "doorState": "CLOSED_LOCKED",
                  "tractionState": "APPLYING",
                  "brakeState": "RELEASED",
                  "currentCollectionStatus": "NORMAL",
                  "tractionAvailable": true,
                  "brakeAvailable": true,
                  "selfCheckStatus": "PASS",
                  "faultLevel": 0,
                  "availableOperationMode": "NORMAL",
                  "dataQuality": "%s",
                  "loadMassKg": 20000.0,
                  "overloadStatus": "NORMAL",
                  "availableTractionCount": 4,
                  "availableBrakeCount": 4,
                  "vehicleProtectionReason": "NONE",
                  "dynamicsState": "ACCELERATING",
                  "dynamicsConstraintReason": "SPEED_MARGIN_AVAILABLE",
                  "speedLimitMetersPerSecond": 22.2,
                  "movementAuthorityDistanceMeters": 1900.0,
                  "stationDistanceMeters": 1000000.0,
                  "stoppingDistanceMeters": 0.0,
                  "tractionCommand": 0.8,
                  "brakeCommand": 0.0,
                  "emergencyBrakeCommand": false,
                  "railVoltage": 1500.0,
                  "powerAvailableWatts": 3200000.0,
                  "faultCode": "%s"
                }
              ],
              "instanceStates": [
                {
                  "trainId": "TR-201",
                  "lifecycleState": "RUNNING",
                  "controlQueueStatus": "DONE",
                  "simulationQueueStatus": "DONE",
                  "lastTick": 1,
                  "latencyMillis": 1,
                  "dataQuality": "%s",
                  "reason": "OK",
                  "updatedAt": "2026-07-09T00:00:00Z"
                }
              ]
            }
            """.formatted(dataQuality, position, faultCode, dataQuality, faultCode, dataQuality);
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
