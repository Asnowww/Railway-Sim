package com.railwaysim.vehicleruntime.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.railwaysim.vehicleruntime.api.VehicleRuntimeController;
import com.railwaysim.vehicleruntime.api.DriverCabInputController;
import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcCodec;
import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehicleParametersLoader;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeBootstrapRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class VehicleRuntimeManagerTests {

    @Test
    void registerCreatesPairedRuntimeInstance() {
        VehicleRuntimeManager manager = manager();

        var state = manager.register(train("TR-101", 100, 0));

        assertThat(state.trainId()).isEqualTo("TR-101");
        assertThat(state.lifecycleState()).isEqualTo("CONTROL_AWAKE");
        assertThat(manager.instances()).singleElement()
            .satisfies(instance -> assertThat(instance.trainId()).isEqualTo("TR-101"));
    }

    @Test
    void registeredSnapshotSeedsAuthoritativeStateForTrainlessFleetTicks() {
        VehicleRuntimeManager manager = manager();
        TrainStateSnapshot initial = train("TR-SEEDED", 432.0, 0);
        manager.register(initial);
        VehicleRuntimeStepRequest request = new VehicleRuntimeStepRequest(
            1, 0.1, Instant.now(), List.of(),
            List.of(new MovementAuthoritySnapshot("TR-SEEDED", 2_000, 22.2, "NORMAL")),
            List.of(new TrackConstraintSnapshot("TR-SEEDED", "SEG-1", 22.2, 0, 1_000, 1_000_000)),
            List.of(), List.of(), "run-seeded", List.of());

        VehicleRuntimeStepResponse response = manager.stepFleet(request);

        assertThat(response.trainOutputs()).singleElement()
            .satisfies(output -> assertThat(output.newPositionMeters()).isGreaterThan(432.0));
        assertThat(response.trainStates()).singleElement()
            .satisfies(state -> assertThat(state.id()).isEqualTo("TR-SEEDED"));
    }

    @Test
    void healthReportsBootstrapStateOnlyAfterCentralConfigurationIsApplied() {
        VehicleRuntimeManager manager = manager();

        assertThat(manager.health().bootstrapped()).isFalse();

        manager.bootstrap(new VehicleRuntimeBootstrapRequest(
            5_000, 22.2, 120, "http://localhost:9200", true));

        assertThat(manager.health().bootstrapped()).isTrue();
    }

    @Test
    void stationTopologyParticipatesInRuntimeConfigIdentity() {
        VehicleRuntimeManager first = manager();
        VehicleRuntimeManager second = manager();
        first.bootstrap(new VehicleRuntimeBootstrapRequest(
            5_000, 22.2, 120, "http://localhost:9200", true,
            List.of(new VehicleRuntimeBootstrapRequest.StationTarget(
                "S01", "Station", 1_250, List.of("P-S01")))));
        second.bootstrap(new VehicleRuntimeBootstrapRequest(
            5_000, 22.2, 120, "http://localhost:9200", true,
            List.of(new VehicleRuntimeBootstrapRequest.StationTarget(
                "S01", "Station", 1_300, List.of("P-S01")))));

        assertThat(first.health().configHash()).isNotEqualTo(second.health().configHash());
    }

    @Test
    void plcInputIsOwnedByRuntimeAndRejectsUnknownInstance() {
        DriverCommandHolder holder = new DriverCommandHolder();
        VehicleRuntimeManager manager = manager();
        DriverCabInputController controller = new DriverCabInputController(holder, manager, 5_000);
        byte[] neutral = new DriverCabPlcCodec().encodeInput(DriverCabPlcInputPacket.neutral());

        assertThat(controller.applyPlcInput("TR-PLC", neutral).getStatusCode().value()).isEqualTo(404);

        manager.register(train("TR-PLC", 100, 0));
        var accepted = controller.applyPlcInput("TR-PLC", neutral);
        assertThat(accepted.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(holder.latest("TR-PLC")).isNotNull();
    }

    @Test
    void plcInputToNewRuntimeStateP95IsBelowTwoHundredMilliseconds() {
        DriverCommandHolder holder = new DriverCommandHolder();
        VehicleRuntimeManager manager = manager();
        manager.register(train("TR-101", 100, 0));
        DriverCabInputController controller = new DriverCabInputController(holder, manager, 5_000);
        byte[] neutral = new DriverCabPlcCodec().encodeInput(DriverCabPlcInputPacket.neutral());
        List<Long> samplesMillis = new ArrayList<>();

        for (int tick = 1; tick <= 50; tick++) {
            long started = System.nanoTime();
            assertThat(controller.applyPlcInput("TR-101", neutral).getStatusCode().is2xxSuccessful()).isTrue();
            VehicleRuntimeStepResponse response = manager.stepFleet(request(tick, train("TR-101", 100, 0), energized()));
            assertThat(response.trainReports()).singleElement().satisfies(report -> {
                assertThat(report.decisionSource()).isEqualTo("DRIVER");
                assertThat(report.inputCommandId()).isNotBlank();
            });
            samplesMillis.add((System.nanoTime() - started) / 1_000_000);
        }

        Collections.sort(samplesMillis);
        long p95 = samplesMillis.get((int) Math.ceil(samplesMillis.size() * 0.95) - 1);
        assertThat(p95).isLessThan(200);
    }

    @Test
    void launchCreatesInstanceAndRegistersWithCentral() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>("");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/trains/runtime-registrations", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"TR-105\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            VehicleRuntimeProperties properties = new VehicleRuntimeProperties();
            properties.setCentralBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            VehicleParameters parameters = parameters(properties);
            VehicleRuntimeManager manager = new VehicleRuntimeManager(
                properties,
                parameters,
                new PowerNetworkLoadClient(properties, RestClient.builder()),
                new CentralTrainRegistrationClient(properties, RestClient.builder()),
                new FmuHttpVehiclePhysicsExecutor(properties, RestClient.builder()),
                new JavaFallbackVehiclePhysicsExecutor(properties, parameters)
            );

            var response = manager.launch(new VehicleRuntimeLaunchRequest(
                "TR-105",
                null,
                8,
                450.0,
                "DOWN",
                true,
                "demo launch",
                "trace-launch"
            ));

            assertThat(response.centralRegistrationStatus()).isEqualTo("REGISTERED");
            assertThat(response.instanceState().lifecycleState()).isEqualTo("CONTROL_AWAKE");
            assertThat(payload.get()).contains("\"trainId\":\"TR-105\"");
            assertThat(payload.get()).contains("\"linkId\":8");
            assertThat(payload.get()).contains("\"offsetMeters\":450.0");
            assertThat(payload.get()).contains("\"trainType\":\"B_TYPE_6_CAR\"");
            assertThat(payload.get()).contains("\"lengthMeters\":118.0");
            assertThat(payload.get()).contains("\"parameterSetId\":\"" + parameters.parameterSetId() + "\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stepFleetRunsControlAndSimulationQueues() {
        VehicleRuntimeManager manager = manager();

        VehicleRuntimeStepResponse response = manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

        assertThat(response.dataQuality()).isEqualTo("GOOD");
        assertThat(response.trainOutputs()).singleElement()
            .satisfies(output -> {
                assertThat(output.trainId()).isEqualTo("TR-101");
                assertThat(output.newPositionMeters()).isGreaterThan(100);
                assertThat(output.tractionPowerWatts()).isGreaterThan(0);
                assertThat(output.railCurrentAmps()).isGreaterThan(0);
                assertThat(output.mechanicalTractionPowerWatts()).isLessThanOrEqualTo(4_336_000);
                assertThat(output.mechanicalTractionPowerWatts())
                    .isCloseTo(output.tractionPowerWatts() * 0.882, within(0.001));
            });
        assertThat(response.trainReports()).singleElement()
            .satisfies(report -> assertThat(report.dynamicsState()).isIn("ACCELERATING", "CRUISING", "COASTING"));
        assertThat(manager.health()).satisfies(health -> {
            assertThat(health.simulationRunId()).isEqualTo("run-test");
            assertThat(health.lastAcceptedTick()).isEqualTo(1);
            assertThat(health.topologyHash()).isEqualTo("NOT_APPLICABLE");
            assertThat(health.configHash()).matches("[0-9a-f]{64}");
            assertThat(health.stoppingParameterVersion()).isEqualTo("STOPPING_V1");
        });
    }

    @Test
    void parameterMetadataExposesCanonicalYamlCalibration() {
        var metadata = manager().parameterMetadata();

        assertThat(metadata.parameterSetId()).matches("sha256:[0-9a-f]{64}");
        assertThat(metadata.parameterSchemaVersion()).isEqualTo("2");
        assertThat(metadata.curveSetId()).matches("sha256:[0-9a-f]{64}");
        assertThat(metadata.emptyMassKg()).isEqualTo(225_000);
        assertThat(metadata.maxLoadMassKg()).isEqualTo(76_000);
        assertThat(metadata.lengthMeters()).isEqualTo(118.0);
        assertThat(metadata.curvePointCount()).isEqualTo(52);
        assertThat(metadata.maxMechanicalTractionPowerWatts()).isEqualTo(4_336_000);
    }

    @Test
    void duplicateTickIsRejectedInsteadOfQueued() {
        VehicleRuntimeManager manager = manager();
        manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

        VehicleRuntimeStepResponse duplicate = manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

        assertThat(duplicate.dataQuality()).isEqualTo("DEGRADED");
        assertThat(duplicate.trainOutputs()).isEmpty();
        assertThat(duplicate.instanceStates()).singleElement()
            .satisfies(state -> {
                assertThat(state.lifecycleState()).isEqualTo("RUNNING");
                assertThat(state.dataQuality()).isEqualTo("GOOD");
                assertThat(state.controlQueueStatus()).isEqualTo("REJECTED");
                assertThat(state.reason()).isEqualTo("STALE_OR_DUPLICATE_TICK");
            });
    }

    @Test
    void newRunStartingAtTickOneResetsPerTrainTickAndRuntimeState() {
        VehicleRuntimeManager manager = manager();
        manager.stepFleet(request(8, "run-old", train("TR-101", 100, 0), energized()));

        VehicleRuntimeStepResponse response = manager.stepFleet(
            request(1, "run-new", train("TR-101", 100, 0), energized())
        );

        assertThat(response.dataQuality()).isEqualTo("GOOD");
        assertThat(response.trainOutputs()).singleElement();
        assertThat(response.instanceStates()).singleElement()
            .satisfies(state -> {
                assertThat(state.lastTick()).isEqualTo(1);
                assertThat(state.lifecycleState()).isEqualTo("RUNNING");
            });
        assertThat(manager.health()).satisfies(health -> {
            assertThat(health.simulationRunId()).isEqualTo("run-new");
            assertThat(health.lastAcceptedTick()).isEqualTo(1);
        });
        assertThat(manager.events()).anySatisfy(event ->
            assertThat(event.eventType()).isEqualTo("RUN_ROLLOVER")
        );
    }

    @Test
    void newRunStartingAfterTickOneIsRejectedWithoutMutatingCurrentRun() {
        VehicleRuntimeManager manager = manager();
        manager.stepFleet(request(1, "run-old", train("TR-101", 100, 0), energized()));

        assertThatThrownBy(() -> manager.stepFleet(
            request(2, "run-new", train("TR-101", 100, 0), energized())
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("VEHICLE_RUN_ID_MISMATCH");
        assertThat(manager.health()).satisfies(health -> {
            assertThat(health.simulationRunId()).isEqualTo("run-old");
            assertThat(health.lastAcceptedTick()).isEqualTo(1);
        });
    }

    @Test
    void duplicateTrainIdInOneFleetBatchIsRejectedBeforeAnyPhysicsStep() {
        VehicleRuntimeManager manager = manager();
        TrainStateSnapshot duplicate = train("TR-DUP", 100, 0);
        VehicleRuntimeStepRequest invalid = new VehicleRuntimeStepRequest(
            1,
            0.1,
            Instant.parse("2026-07-09T00:00:00Z"),
            List.of(duplicate, duplicate),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "run-duplicate",
            List.of()
        );

        assertThatThrownBy(() -> manager.stepFleet(invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate trainId");
        assertThat(manager.instances()).isEmpty();
    }

    @Test
    void registerRejectsPathAndBodyTrainIdMismatch() {
        VehicleRuntimeManager manager = manager();
        VehicleRuntimeController controller = new VehicleRuntimeController(
            manager, DriverCommandHolder.getInstance(),
            new VehicleRuntimeTickClock(manager, new VehicleRuntimeProperties()));

        assertThatThrownBy(() -> controller.register("TR-PATH", train("TR-BODY", 100, 0)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
            );
    }

    @Test
    void powerLossForcesControlQueueToBrakeAndReportCurrentCollectionLost() {
        VehicleRuntimeManager manager = manager();

        VehicleRuntimeStepResponse response = manager.stepFleet(request(1, train("TR-101", 100, 8), deenergized()));

        assertThat(response.trainReports()).singleElement()
            .satisfies(report -> {
                assertThat(report.dynamicsState()).isEqualTo("POWER_LOSS");
                assertThat(report.currentCollectionStatus()).isEqualTo("LOST");
                assertThat(report.faultLevel()).isEqualTo(3);
            });
    }

    @Test
    void driverServiceBrakeControlsExternalRuntimePhysicsInput() {
        // Store driver command in shared holder (PLC input → 9300 direct path)
        DriverCommandHolder.getInstance().clear();
        TrainStateSnapshot train = train("TR-DRIVER", 100, 8);
        DriverControlCommandSnapshot command = new DriverControlCommandSnapshot(
            "driver-1", train.id(), 1, Instant.now(), Instant.now().plusSeconds(5),
            0.8, 0.7, false, 1, false, false, "MANUAL", "trace-driver"
        );
        DriverCommandHolder.getInstance().store(command);

        VehicleRuntimeManager manager = manager();
        VehicleRuntimeStepRequest request = new VehicleRuntimeStepRequest(
            1, 0.1, Instant.now(), List.of(train),
            List.of(new MovementAuthoritySnapshot(train.id(), 2_000, 22.2, "NORMAL")),
            List.of(new TrackConstraintSnapshot(train.id(), "SEG-1", 22.2, 0, 1_000, 1_000_000)),
            List.of(), List.of(energized()), "run-driver", List.of()
        );

        VehicleRuntimeStepResponse response = manager.stepFleet(request);

        assertThat(response.trainOutputs()).singleElement().satisfies(output -> {
            assertThat(output.tractionForceNewtons()).isZero();
            assertThat(output.brakeForceNewtons()).isGreaterThan(0);
        });
        assertThat(response.trainReports()).singleElement().satisfies(report -> {
            assertThat(report.dynamicsConstraintReason()).isEqualTo("DRIVER_SERVICE_BRAKE");
            assertThat(report.decisionSource()).isEqualTo("DRIVER");
            assertThat(report.inputCommandId()).isEqualTo("driver-1");
            assertThat(report.inputTraceId()).isEqualTo("trace-driver");
        });
    }

    @Test
    void stepFleetForwardsAggregatedLoadsToPowerNetworkWhenEnabled() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>("");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/power-network/constraints/query", exchange -> {
            byte[] response = "{\"powerConstraints\":[{\"trainId\":\"TR-101\",\"sectionId\":\"P01\",\"railVoltage\":1500,\"powerAvailableWatts\":3200000,\"energized\":true,\"powerDeratingFactor\":1,\"currentCollectionAvailable\":true,\"regenAvailable\":true,\"constraintReason\":\"NORMAL\"}]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/power-network/step", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = "{\"powerConstraints\":[{\"trainId\":\"TR-101\",\"sectionId\":\"P01\",\"railVoltage\":1490,\"powerAvailableWatts\":3000000,\"energized\":true,\"powerDeratingFactor\":1,\"currentCollectionAvailable\":true,\"regenAvailable\":true,\"constraintReason\":\"NORMAL\"}]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            VehicleRuntimeProperties properties = new VehicleRuntimeProperties();
            properties.setForwardPowerLoads(true);
            properties.setPowerNetworkBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            VehicleParameters parameters = parameters(properties);
            VehicleRuntimeManager manager = new VehicleRuntimeManager(
                properties,
                parameters,
                new PowerNetworkLoadClient(properties, RestClient.builder()),
                new CentralTrainRegistrationClient(properties, RestClient.builder()),
                new FmuHttpVehiclePhysicsExecutor(properties, RestClient.builder()),
                new JavaFallbackVehiclePhysicsExecutor(properties, parameters)
            );

            manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

            assertThat(payload.get()).contains("\"sectionLoads\"");
            assertThat(payload.get()).contains("\"tick\":1");
            assertThat(payload.get()).contains("\"simulationTimeSeconds\":0.1");
            assertThat(payload.get()).contains("\"stepSizeSeconds\":0.1");
            assertThat(payload.get()).contains("\"powerSectionId\":\"P01\"");
            assertThat(payload.get()).contains("\"trainIds\":[\"TR-101\"]");
            assertThat(payload.get()).doesNotContain("mechanicalTractionPowerWatts");
            assertThat(payload.get()).doesNotContain("mechanicalRegenPowerWatts");
        } finally {
            server.stop(0);
        }
    }

    private VehicleRuntimeManager manager() {
        VehicleRuntimeProperties properties = new VehicleRuntimeProperties();
        properties.setQueueCapacity(1);
        VehicleParameters parameters = parameters(properties);
        return new VehicleRuntimeManager(
            properties,
            parameters,
            new PowerNetworkLoadClient(properties, RestClient.builder()),
            new CentralTrainRegistrationClient(properties, RestClient.builder()),
            new FmuHttpVehiclePhysicsExecutor(properties, RestClient.builder()),
            new JavaFallbackVehiclePhysicsExecutor(properties, parameters)
        );
    }

    private VehicleParameters parameters(VehicleRuntimeProperties properties) {
        return VehicleParametersLoader.load(properties.getTrainParamsPath());
    }

    private VehicleRuntimeStepRequest request(long tick, TrainStateSnapshot train, PowerConstraintSnapshot power) {
        return request(tick, "run-test", train, power);
    }

    private VehicleRuntimeStepRequest request(
        long tick,
        String runId,
        TrainStateSnapshot train,
        PowerConstraintSnapshot power
    ) {
        return new VehicleRuntimeStepRequest(
            tick,
            0.1,
            Instant.parse("2026-07-09T00:00:00Z"),
            List.of(train),
            List.of(new MovementAuthoritySnapshot(train.id(), 2_000, 22.2, "NORMAL")),
            List.of(new TrackConstraintSnapshot(train.id(), "SEG-1", 22.2, 0, 1_000, 1_000_000)),
            List.of(),
            List.of(power),
            runId,
            List.of()
        );
    }

    private PowerConstraintSnapshot energized() {
        return new PowerConstraintSnapshot("TR-101", "P01", 1500, 3_200_000, true, 1.0, true, true, "NORMAL");
    }

    private PowerConstraintSnapshot deenergized() {
        return new PowerConstraintSnapshot("TR-101", "P01", 0, 0, false, 0, false, false, "POWER_UNAVAILABLE");
    }

    private TrainStateSnapshot train(String trainId, double position, double speed) {
        return new TrainStateSnapshot(
            trainId,
            "demo-line",
            trainId,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "EXTERNAL_RUNTIME",
            1,
            "DOWN",
            position,
            speed,
            120,
            position,
            Math.max(0, position - 120),
            0.42,
            0,
            "NORMAL",
            4,
            4,
            "NONE",
            "RUNNING",
            "ATO",
            speed <= 0.05,
            "CLOSED_LOCKED",
            "IDLE",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "COASTING",
            "INITIAL",
            22.2,
            0,
            2_000,
            1_000_000,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK"
        );
    }
}
