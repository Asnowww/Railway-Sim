package com.railwaysim.vehicleruntime.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.FmuStepFleetRequestDto;
import com.railwaysim.vehicleruntime.model.FmuStepFleetResponseDto;
import com.railwaysim.vehicleruntime.model.FmuTrainErrorDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter for the Linux/FMPy service.
 * One 100 ms TCMS execution is split into five 20 ms FMU exchanges; the control
 * command is held across the substeps and only the final physical state is
 * returned to the 9300 control loop.
 */
@Component
public class FmuHttpVehiclePhysicsExecutor implements VehiclePhysicsExecutor {

    private static final MediaType MESSAGE_PACK = new MediaType("application", "msgpack");

    private final VehicleRuntimeProperties properties;
    private final List<RestClient> clients;
    private final ExecutorService shardExecutor;
    private final ObjectMapper messagePackMapper = new ObjectMapper(new MessagePackFactory());

    public FmuHttpVehiclePhysicsExecutor(
        VehicleRuntimeProperties properties,
        RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.clients = properties.getEffectiveFmuBaseUrls().stream()
            .map(baseUrl -> createClient(restClientBuilder.clone(), baseUrl))
            .toList();
        this.shardExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Instant startedAt = Instant.now();
        long outerStepMillis = Math.round(request.stepSizeSeconds() * 1000.0);
        long substepMillis = properties.getFmuSubstepMillis();
        if (outerStepMillis <= 0 || outerStepMillis % substepMillis != 0) {
            throw new IllegalStateException(
                "FMU_SUBSTEP_MISMATCH: outer=" + outerStepMillis + "ms, substep=" + substepMillis + "ms"
            );
        }
        int substepCount = Math.toIntExact(outerStepMillis / substepMillis);
        double substepSeconds = substepMillis / 1000.0;
        double outerStartTimeSeconds = request.simulationTimeSeconds() - request.stepSizeSeconds();
        if (outerStartTimeSeconds < -1.0e-9) {
            throw new IllegalStateException("FMU_NEGATIVE_OUTER_START_TIME");
        }

        List<VehiclePhysicsInputDto> activeInputs = new ArrayList<>(request.trains());
        Map<String, VehiclePhysicsOutputDto> finalOutputs = new LinkedHashMap<>();
        Map<String, FmuTrainErrorDto> finalErrors = new LinkedHashMap<>();
        for (int substep = 0; substep < substepCount && !activeInputs.isEmpty(); substep++) {
            long physicsTick = Math.addExact(
                Math.multiplyExact(request.tick() - 1, substepCount),
                substep + 1L
            );
            double simulationTimeSeconds = Math.max(0, outerStartTimeSeconds + substep * substepSeconds);
            FmuStepFleetRequestDto payload = new FmuStepFleetRequestDto(
                physicsTick,
                simulationTimeSeconds,
                substepSeconds,
                request.modelVersion(),
                request.parameterSetId(),
                request.traceId() + "-physics-" + (substep + 1),
                activeInputs
            );
            StepExchange exchange = substep == 0 || !properties.isFmuBinaryProtocolEnabled()
                ? postSharded(payload)
                : postCompactSharded(payload, substep == substepCount - 1);
            finalErrors.putAll(exchange.errors());

            List<VehiclePhysicsInputDto> nextInputs = new ArrayList<>();
            for (VehiclePhysicsInputDto input : activeInputs) {
                VehiclePhysicsOutputDto output = exchange.outputs().get(input.trainId());
                if (output != null) {
                    finalOutputs.put(input.trainId(), output);
                    nextInputs.add(nextSubstepInput(input, output, substepSeconds));
                }
            }
            activeInputs = nextInputs;
        }
        finalErrors.keySet().forEach(finalOutputs::remove);
        return new ExecutionResult(
            finalOutputs,
            finalErrors,
            Duration.between(startedAt, Instant.now()).toMillis()
        );
    }

    private StepExchange postSharded(FmuStepFleetRequestDto payload) {
        List<List<VehiclePhysicsInputDto>> partitions = new ArrayList<>(clients.size());
        for (int index = 0; index < clients.size(); index++) {
            partitions.add(new ArrayList<>());
        }
        payload.trains().forEach(train -> partitions.get(shardIndex(train.trainId())).add(train));

        List<CompletableFuture<StepExchange>> futures = new ArrayList<>();
        for (int index = 0; index < clients.size(); index++) {
            List<VehiclePhysicsInputDto> shardTrains = partitions.get(index);
            if (shardTrains.isEmpty()) {
                continue;
            }
            int shardIndex = index;
            FmuStepFleetRequestDto shardPayload = new FmuStepFleetRequestDto(
                payload.tick(), payload.simulationTimeSeconds(), payload.stepSizeSeconds(),
                payload.modelVersion(), payload.parameterSetId(), payload.traceId(), shardTrains
            );
            if (clients.size() == 1) {
                futures.add(CompletableFuture.completedFuture(postShard(shardIndex, shardPayload)));
            } else {
                futures.add(CompletableFuture.supplyAsync(
                    () -> postShard(shardIndex, shardPayload), shardExecutor
                ));
            }
        }

        Map<String, VehiclePhysicsOutputDto> outputs = new LinkedHashMap<>();
        Map<String, FmuTrainErrorDto> errors = new LinkedHashMap<>();
        futures.stream().map(CompletableFuture::join).forEach(exchange -> {
            exchange.outputs().forEach((trainId, output) -> {
                if (outputs.putIfAbsent(trainId, output) != null) {
                    throw new IllegalStateException("FMU_DUPLICATE_OUTPUT_ACROSS_SHARDS");
                }
            });
            exchange.errors().forEach((trainId, error) -> {
                if (errors.putIfAbsent(trainId, error) != null) {
                    throw new IllegalStateException("FMU_DUPLICATE_ERROR_ACROSS_SHARDS");
                }
            });
        });
        return new StepExchange(outputs, errors);
    }

    private StepExchange postCompactSharded(
        FmuStepFleetRequestDto payload,
        boolean fullResponse
    ) {
        List<List<VehiclePhysicsInputDto>> partitions = partition(payload.trains());
        List<CompletableFuture<StepExchange>> futures = new ArrayList<>();
        for (int index = 0; index < clients.size(); index++) {
            List<VehiclePhysicsInputDto> shardTrains = partitions.get(index);
            if (shardTrains.isEmpty()) {
                continue;
            }
            int shardIndex = index;
            FmuStepFleetRequestDto shardPayload = new FmuStepFleetRequestDto(
                payload.tick(), payload.simulationTimeSeconds(), payload.stepSizeSeconds(),
                payload.modelVersion(), payload.parameterSetId(), payload.traceId(), shardTrains
            );
            futures.add(clients.size() == 1
                ? CompletableFuture.completedFuture(postCompactShard(shardIndex, shardPayload, fullResponse))
                : CompletableFuture.supplyAsync(
                    () -> postCompactShard(shardIndex, shardPayload, fullResponse), shardExecutor
                ));
        }
        return mergeShardExchanges(futures);
    }

    private List<List<VehiclePhysicsInputDto>> partition(List<VehiclePhysicsInputDto> trains) {
        List<List<VehiclePhysicsInputDto>> partitions = new ArrayList<>(clients.size());
        for (int index = 0; index < clients.size(); index++) {
            partitions.add(new ArrayList<>());
        }
        trains.forEach(train -> partitions.get(shardIndex(train.trainId())).add(train));
        return partitions;
    }

    private StepExchange mergeShardExchanges(List<CompletableFuture<StepExchange>> futures) {
        Map<String, VehiclePhysicsOutputDto> outputs = new LinkedHashMap<>();
        Map<String, FmuTrainErrorDto> errors = new LinkedHashMap<>();
        futures.stream().map(CompletableFuture::join).forEach(exchange -> {
            exchange.outputs().forEach((trainId, output) -> {
                if (outputs.putIfAbsent(trainId, output) != null) {
                    throw new IllegalStateException("FMU_DUPLICATE_OUTPUT_ACROSS_SHARDS");
                }
            });
            exchange.errors().forEach((trainId, error) -> {
                if (errors.putIfAbsent(trainId, error) != null) {
                    throw new IllegalStateException("FMU_DUPLICATE_ERROR_ACROSS_SHARDS");
                }
            });
        });
        return new StepExchange(outputs, errors);
    }

    private StepExchange postCompactShard(
        int shardIndex,
        FmuStepFleetRequestDto payload,
        boolean fullResponse
    ) {
        try {
            List<List<Object>> updates = payload.trains().stream()
                .map(train -> List.<Object>of(
                    train.trainId(), train.positionMeters(), train.speedMetersPerSecond(),
                    train.previousEnergyConsumedKwh(), train.previousEnergyRegeneratedKwh()
                ))
                .toList();
            Map<String, Object> compactPayload = new LinkedHashMap<>();
            compactPayload.put("t", payload.tick());
            compactPayload.put("s", payload.simulationTimeSeconds());
            compactPayload.put("d", payload.stepSizeSeconds());
            compactPayload.put("m", payload.modelVersion());
            compactPayload.put("p", payload.parameterSetId());
            compactPayload.put("r", payload.traceId());
            compactPayload.put("f", fullResponse);
            compactPayload.put("u", updates);
            byte[] responseBody = clients.get(shardIndex)
                .post()
                .uri("/step-fleet-compact")
                .contentType(MESSAGE_PACK)
                .accept(MESSAGE_PACK)
                .body(messagePackMapper.writeValueAsBytes(compactPayload))
                .retrieve()
                .body(byte[].class);
            if (responseBody == null) {
                throw new IllegalStateException("FMU_EMPTY_COMPACT_RESPONSE");
            }
            if (fullResponse) {
                FmuStepFleetResponseDto response = messagePackMapper.readValue(
                    responseBody, FmuStepFleetResponseDto.class
                );
                return validateAndIndex(payload, response);
            }
            return validateAndIndexCompact(
                payload,
                messagePackMapper.readValue(responseBody, List.class)
            );
        } catch (Exception exception) {
            return shardFailure(shardIndex, payload, exception);
        }
    }

    private StepExchange postShard(int shardIndex, FmuStepFleetRequestDto payload) {
        try {
            FmuStepFleetResponseDto response;
            if (properties.isFmuBinaryProtocolEnabled()) {
                byte[] requestBody = messagePackMapper.writeValueAsBytes(payload);
                byte[] responseBody = clients.get(shardIndex)
                    .post()
                    .uri("/step-fleet-msgpack")
                    .contentType(MESSAGE_PACK)
                    .accept(MESSAGE_PACK)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
                response = responseBody == null
                    ? null
                    : messagePackMapper.readValue(responseBody, FmuStepFleetResponseDto.class);
            } else {
                response = clients.get(shardIndex)
                    .post()
                    .uri("/step-fleet")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(FmuStepFleetResponseDto.class);
            }
            if (response == null) {
                throw new IllegalStateException("FMU_EMPTY_RESPONSE");
            }
            return validateAndIndex(payload, response);
        } catch (Exception exception) {
            return shardFailure(shardIndex, payload, exception);
        }
    }

    private StepExchange shardFailure(
        int shardIndex,
        FmuStepFleetRequestDto payload,
        Exception exception
    ) {
        Map<String, FmuTrainErrorDto> errors = new LinkedHashMap<>();
        payload.trains().forEach(train -> errors.put(train.trainId(), new FmuTrainErrorDto(
            train.trainId(), "FMU_SHARD_UNAVAILABLE",
            "shard=" + shardIndex + ":" + exception.getClass().getSimpleName(),
            "UNKNOWN", "ERROR", "ERROR"
        )));
        return new StepExchange(Map.of(), errors);
    }

    private StepExchange validateAndIndex(
        FmuStepFleetRequestDto request,
        FmuStepFleetResponseDto response
    ) {
        validateEnvelope(request, response);
        Map<String, VehiclePhysicsOutputDto> outputs = new LinkedHashMap<>();
        for (VehiclePhysicsOutputDto output : response.trainOutputs()) {
            if (output.trainId() == null || outputs.putIfAbsent(output.trainId(), output) != null) {
                throw new IllegalStateException("FMU_DUPLICATE_OR_MISSING_OUTPUT_TRAIN_ID");
            }
        }
        Map<String, FmuTrainErrorDto> errors = new LinkedHashMap<>();
        for (FmuTrainErrorDto error : response.trainErrors()) {
            if (error.trainId() == null || errors.putIfAbsent(error.trainId(), error) != null) {
                throw new IllegalStateException("FMU_DUPLICATE_OR_MISSING_ERROR_TRAIN_ID");
            }
        }
        Set<String> requested = new HashSet<>();
        request.trains().forEach(train -> requested.add(train.trainId()));
        Set<String> returned = new HashSet<>(outputs.keySet());
        returned.addAll(errors.keySet());
        if (!requested.equals(returned)) {
            throw new IllegalStateException("FMU_RESPONSE_TRAIN_SET_MISMATCH");
        }
        Set<String> overlap = new HashSet<>(outputs.keySet());
        overlap.retainAll(errors.keySet());
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("FMU_OUTPUT_ERROR_OVERLAP");
        }
        return new StepExchange(outputs, errors);
    }

    private StepExchange validateAndIndexCompact(
        FmuStepFleetRequestDto request,
        List<?> response
    ) {
        if (response.size() != 6
            || ((Number) response.get(0)).longValue() != request.tick()
            || !request.modelVersion().equals(response.get(1))
            || !request.parameterSetId().equals(response.get(2))
            || !request.traceId().equals(response.get(3))) {
            throw new IllegalStateException("FMU_COMPACT_RESPONSE_ENVELOPE_MISMATCH");
        }
        Map<String, VehiclePhysicsOutputDto> outputs = new LinkedHashMap<>();
        for (Object value : (List<?>) response.get(4)) {
            List<?> output = (List<?>) value;
            if (output.size() != 5) {
                throw new IllegalStateException("FMU_INVALID_COMPACT_OUTPUT");
            }
            String trainId = (String) output.get(0);
            VehiclePhysicsOutputDto mapped = new VehiclePhysicsOutputDto(
                trainId,
                ((Number) output.get(1)).doubleValue(),
                ((Number) output.get(2)).doubleValue(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                ((Number) output.get(3)).doubleValue(),
                ((Number) output.get(4)).doubleValue(),
                "OK", "ACTIVE", "GOOD", "OK"
            );
            if (trainId == null || outputs.putIfAbsent(trainId, mapped) != null) {
                throw new IllegalStateException("FMU_DUPLICATE_OR_MISSING_OUTPUT_TRAIN_ID");
            }
        }
        Map<String, FmuTrainErrorDto> errors = new LinkedHashMap<>();
        for (Object value : (List<?>) response.get(5)) {
            List<?> error = (List<?>) value;
            if (error.size() != 6) {
                throw new IllegalStateException("FMU_INVALID_COMPACT_ERROR");
            }
            String trainId = (String) error.get(0);
            FmuTrainErrorDto mapped = new FmuTrainErrorDto(
                trainId,
                (String) error.get(1),
                (String) error.get(2),
                (String) error.get(3),
                (String) error.get(4),
                (String) error.get(5)
            );
            if (trainId == null || errors.putIfAbsent(trainId, mapped) != null) {
                throw new IllegalStateException("FMU_DUPLICATE_OR_MISSING_ERROR_TRAIN_ID");
            }
        }
        Set<String> requested = new HashSet<>();
        request.trains().forEach(train -> requested.add(train.trainId()));
        Set<String> returned = new HashSet<>(outputs.keySet());
        returned.addAll(errors.keySet());
        if (!requested.equals(returned)) {
            throw new IllegalStateException("FMU_RESPONSE_TRAIN_SET_MISMATCH");
        }
        Set<String> overlap = new HashSet<>(outputs.keySet());
        overlap.retainAll(errors.keySet());
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("FMU_OUTPUT_ERROR_OVERLAP");
        }
        return new StepExchange(outputs, errors);
    }

    @Override
    public void deleteInstance(String trainId) {
        clients.get(shardIndex(trainId)).delete()
            .uri("/instances/{trainId}", trainId).retrieve().toBodilessEntity();
    }

    @Override
    public void resetAll() {
        clients.stream()
            .map(client -> CompletableFuture.runAsync(
                () -> client.post().uri("/instances/reset-all").retrieve().toBodilessEntity(),
                shardExecutor
            ))
            .toList()
            .forEach(CompletableFuture::join);
    }

    @PreDestroy
    void close() {
        shardExecutor.close();
    }

    private int shardIndex(String trainId) {
        return Math.floorMod(trainId.hashCode(), clients.size());
    }

    private void validateEnvelope(FmuStepFleetRequestDto request, FmuStepFleetResponseDto response) {
        if (response.tick() != request.tick()
            || !request.modelVersion().equals(response.modelVersion())
            || !request.parameterSetId().equals(response.parameterSetId())
            || !request.traceId().equals(response.traceId())) {
            throw new IllegalStateException("FMU_RESPONSE_ENVELOPE_MISMATCH");
        }
    }

    private VehiclePhysicsInputDto nextSubstepInput(
        VehiclePhysicsInputDto input,
        VehiclePhysicsOutputDto output,
        double substepSeconds
    ) {
        return new VehiclePhysicsInputDto(
            input.trainId(), "STEP", input.sectionId(), output.newPositionMeters(),
            output.newSpeedMetersPerSecond(), input.trainMassKg(), input.tractionCommand(), input.brakeCommand(),
            input.emergencyBrakeCommand(), input.speedLimitMetersPerSecond(), input.movementAuthorityDistanceMeters(),
            input.gradient(), input.curveRadiusMeters(), input.railVoltage(), input.powerAvailableWatts(),
            input.regenPowerAvailableWatts(), input.currentCollectionAvailable(), input.doorClosed(),
            input.adhesionCoefficient(), output.energyConsumedKwh(), output.energyRegeneratedKwh(), substepSeconds,
            input.dynamicsState(), input.dynamicsConstraintReason(), input.stationDistanceMeters(),
            input.stoppingDistanceMeters()
        );
    }

    private record StepExchange(
        Map<String, VehiclePhysicsOutputDto> outputs,
        Map<String, FmuTrainErrorDto> errors
    ) {
    }

    private RestClient createClient(RestClient.Builder restClientBuilder, String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1, properties.getFmuTimeoutMillis()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }
}
