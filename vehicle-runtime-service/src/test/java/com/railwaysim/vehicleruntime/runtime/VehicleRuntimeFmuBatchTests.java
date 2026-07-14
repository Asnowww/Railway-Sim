package com.railwaysim.vehicleruntime.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehicleParametersLoader;
import com.railwaysim.vehicleruntime.config.VehiclePhysicsMode;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class VehicleRuntimeFmuBatchTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lifecycleMutationWaitsForInFlightFleetStep() throws Exception {
        CountDownLatch stepEntered = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/step-fleet", exchange -> {
            JsonNode request = read(exchange);
            stepEntered.countDown();
            try {
                if (!releaseStep.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release FMU step");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            write(exchange, 200, successResponse(request, null));
        });
        server.createContext("/instances/TR-SYNC", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            VehicleRuntimeManager manager = fmuManager(server, 2_000);
            CompletableFuture<Void> step = CompletableFuture.runAsync(
                () -> manager.stepFleet(request(1, List.of(train("TR-SYNC", 100, 0))))
            );
            assertThat(stepEntered.await(1, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> removal = CompletableFuture.runAsync(() -> manager.remove("TR-SYNC"));
            Thread.sleep(50);
            assertThat(removal).isNotDone();

            releaseStep.countDown();
            step.get(2, TimeUnit.SECONDS);
            removal.get(2, TimeUnit.SECONDS);
            assertThat(manager.instances()).isEmpty();
        } finally {
            releaseStep.countDown();
            server.stop(0);
        }
    }

    @Test
    void thousandTrainsAreStablePartitionedAcrossFmuShards() throws Exception {
        List<HttpServer> servers = new ArrayList<>();
        List<AtomicInteger> requestCounts = new ArrayList<>();
        ConcurrentMap<String, java.util.Set<Integer>> shardByTrain = new ConcurrentHashMap<>();
        try {
            for (int shard = 0; shard < 4; shard++) {
                int shardIndex = shard;
                AtomicInteger requestCount = new AtomicInteger();
                requestCounts.add(requestCount);
                HttpServer server = fmuServer(exchange -> {
                    requestCount.incrementAndGet();
                    JsonNode request = read(exchange);
                    request.path("trains").forEach(train -> shardByTrain
                        .computeIfAbsent(train.path("trainId").asText(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(shardIndex));
                    write(exchange, 200, successResponse(request, null));
                });
                servers.add(server);
            }
            VehicleRuntimeManager manager = fmuManager(servers, 80);
            List<TrainStateSnapshot> trains = new ArrayList<>();
            for (int index = 0; index < 1_000; index++) {
                trains.add(train("SHARD-TR-" + index, 100 + index * 5, 0));
            }

            VehicleRuntimeStepResponse response = manager.stepFleet(request(1, trains));

            assertThat(response.trainOutputs()).hasSize(1_000);
            assertThat(response.dataQuality()).isEqualTo("GOOD");
            assertThat(requestCounts).allSatisfy(count -> assertThat(count).hasValue(5));
            assertThat(shardByTrain).hasSize(1_000);
            assertThat(shardByTrain.values()).allSatisfy(shards -> assertThat(shards).hasSize(1));
        } finally {
            servers.forEach(server -> server.stop(0));
        }
    }

    @Test
    void twentyTrainsUseFiveTwentyMillisecondFmuExchangesPerTcmsTick() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = fmuServer(exchange -> {
            requestCount.incrementAndGet();
            JsonNode request = read(exchange);
            requests.add(request);
            write(exchange, 200, successResponse(request, null));
        });
        try {
            VehicleRuntimeManager manager = fmuManager(server, 80);
            List<TrainStateSnapshot> trains = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                trains.add(train("TR-" + index, 100 + index * 5, 0));
            }

            VehicleRuntimeStepResponse response = manager.stepFleet(request(1, trains));

            assertThat(requestCount).hasValue(5);
            assertThat(requests).allSatisfy(requestBody -> {
                assertThat(requestBody.path("trains")).hasSize(20);
                assertThat(requestBody.path("stepSizeSeconds").asDouble()).isEqualTo(0.02);
                assertThat(requestBody.path("trains").get(0).has("deltaSeconds")).isFalse();
            });
            assertThat(requests).extracting(requestBody -> requestBody.path("tick").asLong())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(requests.get(0).path("simulationTimeSeconds").asDouble()).isZero();
            assertThat(requests.get(4).path("simulationTimeSeconds").asDouble()).isEqualTo(0.08);
            assertThat(requests.get(0).path("trains").get(0).path("lifecycleCommand").asText()).isEqualTo("INIT");
            assertThat(requests.get(4).path("trains").get(0).path("lifecycleCommand").asText()).isEqualTo("STEP");
            assertThat(response.trainOutputs()).hasSize(20);
            assertThat(response.trainReports()).hasSize(20);
            assertThat(response.dataQuality()).isEqualTo("GOOD");
            assertThat(manager.health().physicsMode()).isEqualTo("FMU_HTTP");
            assertThat(manager.health().fallbackTrainCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oneTrainErrorFallsBackOnlyThatTrainAndFallbackIsStickyUntilResync() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = fmuServer(exchange -> {
            JsonNode request = read(exchange);
            requests.add(request);
            int invocation = requestCount.incrementAndGet();
            write(exchange, 200, successResponse(request, invocation == 1 ? "TR-BAD" : null));
        });
        try {
            VehicleRuntimeManager manager = fmuManager(server, 80);
            List<TrainStateSnapshot> trains = List.of(train("TR-GOOD", 100, 0), train("TR-BAD", 200, 0));

            VehicleRuntimeStepResponse first = manager.stepFleet(request(1, trains));
            VehicleRuntimeStepResponse second = manager.stepFleet(request(2, updateFrom(first, trains)));

            assertThat(first.trainOutputs()).filteredOn(output -> output.trainId().equals("TR-BAD"))
                .singleElement().satisfies(output -> assertThat(output.faultCode()).isEqualTo("EXTERNAL_SIM_FALLBACK"));
            assertThat(first.trainOutputs()).filteredOn(output -> output.trainId().equals("TR-GOOD"))
                .singleElement().satisfies(output -> assertThat(output.faultCode()).isEqualTo("OK"));
            assertThat(requests.get(1).path("trains")).hasSize(1);
            assertThat(requests.get(1).path("trains").get(0).path("trainId").asText()).isEqualTo("TR-GOOD");
            assertThat(manager.health().fallbackTrainCount()).isEqualTo(1);
            assertThat(manager.health().fmiErrorCount()).isEqualTo(1);

            manager.resyncPhysics("TR-BAD");
            VehicleRuntimeStepResponse third = manager.stepFleet(request(3, updateFrom(second, trains)));

            assertThat(requests.get(10).path("trains")).hasSize(2);
            JsonNode bad = findTrain(requests.get(10), "TR-BAD");
            assertThat(bad.path("lifecycleCommand").asText()).isEqualTo("RESYNC");
            assertThat(third.trainOutputs()).allMatch(output -> output.faultCode().equals("OK"));
            assertThat(manager.health().fallbackTrainCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fmuTimeoutFallsBackWholeFleetWithinTheTickAndMarksHealthDegraded() throws Exception {
        HttpServer server = fmuServer(exchange -> {
            JsonNode request = read(exchange);
            try {
                Thread.sleep(100);
                write(exchange, 200, successResponse(request, null));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client is expected to close the timed-out exchange.
            }
        });
        try {
            VehicleRuntimeManager manager = fmuManager(server, 10);

            VehicleRuntimeStepResponse response = manager.stepFleet(request(
                1,
                List.of(train("TR-A", 100, 0), train("TR-B", 200, 0))
            ));

            assertThat(response.trainOutputs()).hasSize(2).allMatch(output -> output.faultCode().equals("EXTERNAL_SIM_FALLBACK"));
            assertThat(response.dataQuality()).isEqualTo("DEGRADED");
            assertThat(manager.health().dataQuality()).isEqualTo("DEGRADED");
            assertThat(manager.health().fallbackTrainCount()).isEqualTo(2);
            assertThat(manager.health().reason()).isEqualTo("PHYSICS_FALLBACK_ACTIVE");
            assertThat(manager.health().totalFleetTickCount()).isEqualTo(1);
            assertThat(manager.health().fallbackEventCount()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void removingAndClearingRuntimeInstancesReleaseRemoteFmuResources() throws Exception {
        List<String> managementCalls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/step-fleet", exchange -> {
            JsonNode request = read(exchange);
            write(exchange, 200, successResponse(request, null));
        });
        server.createContext("/instances/", exchange -> {
            managementCalls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            VehicleRuntimeManager manager = fmuManager(server, 80);
            List<TrainStateSnapshot> trains = List.of(train("TR-REMOVE", 100, 0), train("TR-CLEAR", 200, 0));
            manager.stepFleet(request(1, trains));

            manager.remove("TR-REMOVE");
            manager.clear();

            assertThat(managementCalls).contains(
                "DELETE /instances/TR-REMOVE",
                "POST /instances/reset-all"
            );
            assertThat(manager.instances()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private VehicleRuntimeManager fmuManager(HttpServer server, long timeoutMillis) {
        return fmuManager(List.of(server), timeoutMillis);
    }

    private VehicleRuntimeManager fmuManager(List<HttpServer> servers, long timeoutMillis) {
        VehicleRuntimeProperties properties = new VehicleRuntimeProperties();
        properties.setPhysicsMode(VehiclePhysicsMode.FMU_HTTP);
        properties.setFmuBaseUrls(servers.stream()
            .map(server -> "http://127.0.0.1:" + server.getAddress().getPort())
            .collect(java.util.stream.Collectors.joining(",")));
        properties.setFmuTimeoutMillis(timeoutMillis);
        VehicleParameters parameters = VehicleParametersLoader.load(properties.getTrainParamsPath());
        return new VehicleRuntimeManager(
            properties,
            parameters,
            new PowerNetworkLoadClient(properties, RestClient.builder()),
            new CentralTrainRegistrationClient(properties, RestClient.builder()),
            new FmuHttpVehiclePhysicsExecutor(properties, RestClient.builder()),
            new JavaFallbackVehiclePhysicsExecutor(properties, parameters)
        );
    }

    private VehicleRuntimeStepRequest request(long tick, List<TrainStateSnapshot> trains) {
        List<MovementAuthoritySnapshot> authorities = trains.stream()
            .map(train -> new MovementAuthoritySnapshot(train.id(), 5_000, 22.2, "NORMAL"))
            .toList();
        List<TrackConstraintSnapshot> tracks = trains.stream()
            .map(train -> new TrackConstraintSnapshot(train.id(), "SEG-1", 22.2, 0, 1_000, 1_000_000))
            .toList();
        List<PowerConstraintSnapshot> power = trains.stream()
            .map(train -> new PowerConstraintSnapshot(train.id(), "P01", 1500, 4_000_000, 1_000_000.0, true, 1, true, true, "NORMAL"))
            .toList();
        return new VehicleRuntimeStepRequest(
            tick, 0.1, Instant.now(), trains, authorities, tracks, List.of(), power, "run-fmu-batch", List.of()
        );
    }

    private List<TrainStateSnapshot> updateFrom(VehicleRuntimeStepResponse response, List<TrainStateSnapshot> originals) {
        return originals.stream().map(original -> response.trainOutputs().stream()
            .filter(output -> output.trainId().equals(original.id()))
            .findFirst()
            .map(output -> train(original.id(), output.newPositionMeters(), output.newSpeedMetersPerSecond()))
            .orElse(original)
        ).toList();
    }

    private TrainStateSnapshot train(String trainId, double position, double speed) {
        return new TrainStateSnapshot(
            trainId, "demo-line", trainId, "IN_SERVICE", "ATTACHED", "ATTACHED", "EXTERNAL_RUNTIME",
            1, "DOWN", position, speed, 120, position, Math.max(0, position - 120), 0.42, 0,
            "NORMAL", 4, 4, "NONE", "RUNNING", "ATO", speed <= 0.05, "CLOSED_LOCKED", "IDLE",
            "RELEASED", "NORMAL", true, true, "PASS", 0, "NORMAL", "GOOD", "COASTING", "INITIAL",
            22.2, 0, 5_000, 1_000_000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "OK"
        );
    }

    private static HttpServer fmuServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/step-fleet", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static JsonNode read(HttpExchange exchange) throws IOException {
        return JSON.readTree(exchange.getRequestBody());
    }

    private static void write(HttpExchange exchange, int status, JsonNode response) throws IOException {
        byte[] body = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static JsonNode successResponse(JsonNode request, String failedTrainId) {
        ObjectNode response = JSON.createObjectNode();
        response.put("tick", request.path("tick").asLong());
        response.put("modelVersion", request.path("modelVersion").asText());
        response.put("parameterSetId", request.path("parameterSetId").asText());
        response.put("traceId", request.path("traceId").asText());
        ArrayNode outputs = response.putArray("trainOutputs");
        ArrayNode errors = response.putArray("trainErrors");
        request.path("trains").forEach(train -> {
            String trainId = train.path("trainId").asText();
            if (trainId.equals(failedTrainId)) {
                ObjectNode error = errors.addObject();
                error.put("trainId", trainId);
                error.put("faultCode", "FMU_STEP_FAILED");
                error.put("message", "injected failure");
                error.put("instanceState", "FAILED");
                error.put("dataQuality", "INVALID");
                error.put("fmiStatus", "ERROR");
                return;
            }
            ObjectNode output = outputs.addObject();
            double speed = train.path("speedMetersPerSecond").asDouble();
            double stepSizeSeconds = request.path("stepSizeSeconds").asDouble();
            output.put("trainId", trainId);
            output.put("newPositionMeters", train.path("positionMeters").asDouble() + Math.max(speed, 1) * stepSizeSeconds);
            output.put("newSpeedMetersPerSecond", speed + 0.05);
            output.put("accelerationMetersPerSecondSquared", 0.5);
            output.put("tractionForceNewtons", 100_000);
            output.put("brakeForceNewtons", 0);
            output.put("regenBrakeForceNewtons", 0);
            output.put("mechanicalTractionPowerWatts", 100_000);
            output.put("tractionPowerWatts", 113_636.36);
            output.put("railCurrentAmps", 75.76);
            output.put("mechanicalRegenPowerWatts", 0);
            output.put("regenPowerWatts", 0);
            output.put("energyConsumedKwh", train.path("previousEnergyConsumedKwh").asDouble() + 0.03 * stepSizeSeconds);
            output.put("energyRegeneratedKwh", train.path("previousEnergyRegeneratedKwh").asDouble());
            output.put("faultCode", "OK");
            output.put("instanceState", "ACTIVE");
            output.put("dataQuality", "GOOD");
            output.put("fmiStatus", "OK");
        });
        return response;
    }

    private static JsonNode findTrain(JsonNode request, String trainId) {
        for (JsonNode train : request.path("trains")) {
            if (trainId.equals(train.path("trainId").asText())) {
                return train;
            }
        }
        throw new AssertionError("train not found: " + trainId);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
