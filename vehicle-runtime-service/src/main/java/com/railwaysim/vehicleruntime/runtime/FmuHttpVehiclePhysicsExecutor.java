package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.FmuStepFleetRequestDto;
import com.railwaysim.vehicleruntime.model.FmuStepFleetResponseDto;
import com.railwaysim.vehicleruntime.model.FmuTrainErrorDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP adapter for the Linux/FMPy service. Exactly one POST is issued per execute call. */
@Component
public class FmuHttpVehiclePhysicsExecutor implements VehiclePhysicsExecutor {

    private final VehicleRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;

    public FmuHttpVehiclePhysicsExecutor(
        VehicleRuntimeProperties properties,
        RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Instant startedAt = Instant.now();
        FmuStepFleetRequestDto payload = new FmuStepFleetRequestDto(
            request.tick(),
            request.simulationTimeSeconds(),
            request.stepSizeSeconds(),
            request.modelVersion(),
            request.parameterSetId(),
            request.traceId(),
            request.trains()
        );
        FmuStepFleetResponseDto response = client()
            .post()
            .uri("/step-fleet")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(FmuStepFleetResponseDto.class);
        if (response == null) {
            throw new IllegalStateException("FMU_EMPTY_RESPONSE");
        }
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
        return new ExecutionResult(
            outputs,
            errors,
            Duration.between(startedAt, Instant.now()).toMillis()
        );
    }

    @Override
    public void deleteInstance(String trainId) {
        client().delete().uri("/instances/{trainId}", trainId).retrieve().toBodilessEntity();
    }

    @Override
    public void resetAll() {
        client().post().uri("/instances/reset-all").retrieve().toBodilessEntity();
    }

    private void validateEnvelope(ExecutionRequest request, FmuStepFleetResponseDto response) {
        if (response.tick() != request.tick()
            || !request.modelVersion().equals(response.modelVersion())
            || !request.parameterSetId().equals(response.parameterSetId())
            || !request.traceId().equals(response.traceId())) {
            throw new IllegalStateException("FMU_RESPONSE_ENVELOPE_MISMATCH");
        }
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1, properties.getFmuTimeoutMillis()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return restClientBuilder
            .baseUrl(properties.getFmuBaseUrl())
            .requestFactory(requestFactory)
            .build();
    }
}
