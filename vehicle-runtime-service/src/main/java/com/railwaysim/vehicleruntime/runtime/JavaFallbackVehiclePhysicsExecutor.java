package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import com.railwaysim.vehicleruntime.model.FmuTrainErrorDto;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** YAML-backed local solver used for rollback mode and deterministic per-train fallback. */
@Component
public class JavaFallbackVehiclePhysicsExecutor implements VehiclePhysicsExecutor {

    private final VehicleRuntimeProperties properties;
    private final VehicleParameters parameters;
    private final Map<String, VehicleSimulationQueue> simulations = new ConcurrentHashMap<>();

    public JavaFallbackVehiclePhysicsExecutor(VehicleRuntimeProperties properties, VehicleParameters parameters) {
        this.properties = properties;
        this.parameters = parameters;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Instant startedAt = Instant.now();
        Map<String, VehiclePhysicsOutputDto> outputs = new LinkedHashMap<>();
        Map<String, FmuTrainErrorDto> errors = new LinkedHashMap<>();
        for (VehiclePhysicsInputDto input : request.trains()) {
            try {
                VehicleSimulationQueue queue = simulations.computeIfAbsent(
                    input.trainId(),
                    ignored -> new VehicleSimulationQueue(properties, parameters)
                );
                outputs.put(input.trainId(), queue.step(request.tick(), input));
            } catch (RuntimeException exception) {
                errors.put(input.trainId(), new FmuTrainErrorDto(
                    input.trainId(),
                    "JAVA_FALLBACK_FAILED",
                    exception.getMessage(),
                    "FAILED",
                    "INVALID",
                    "ERROR"
                ));
            }
        }
        return new ExecutionResult(
            outputs,
            errors,
            Duration.between(startedAt, Instant.now()).toMillis()
        );
    }

    @Override
    public void deleteInstance(String trainId) {
        simulations.remove(trainId);
    }

    @Override
    public void resetAll() {
        simulations.clear();
    }
}
