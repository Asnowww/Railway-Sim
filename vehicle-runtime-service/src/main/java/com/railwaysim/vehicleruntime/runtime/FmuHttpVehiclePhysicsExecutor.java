package com.railwaysim.vehicleruntime.runtime;

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
            FmuStepFleetResponseDto response = post(payload);
            StepExchange exchange = validateAndIndex(payload, response);
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

    private FmuStepFleetResponseDto post(FmuStepFleetRequestDto payload) {
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
        return response;
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

    @Override
    public void deleteInstance(String trainId) {
        client().delete().uri("/instances/{trainId}", trainId).retrieve().toBodilessEntity();
    }

    @Override
    public void resetAll() {
        client().post().uri("/instances/reset-all").retrieve().toBodilessEntity();
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
