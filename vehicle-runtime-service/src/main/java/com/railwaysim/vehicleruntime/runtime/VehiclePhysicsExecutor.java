package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.model.FmuTrainErrorDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import java.util.List;
import java.util.Map;

/** Fleet-level physics boundary. One invocation represents one 100 ms fleet tick. */
public interface VehiclePhysicsExecutor {

    ExecutionResult execute(ExecutionRequest request);

    default void deleteInstance(String trainId) {
        // A stateless executor has no remote resource to release.
    }

    default void resetAll() {
        // A stateless executor has no remote resource to release.
    }

    record ExecutionRequest(
        long tick,
        double simulationTimeSeconds,
        double stepSizeSeconds,
        String modelVersion,
        String parameterSetId,
        String traceId,
        List<VehiclePhysicsInputDto> trains
    ) {
        public ExecutionRequest {
            trains = trains == null ? List.of() : List.copyOf(trains);
        }
    }

    record ExecutionResult(
        Map<String, VehiclePhysicsOutputDto> outputs,
        Map<String, FmuTrainErrorDto> errors,
        long latencyMillis
    ) {
        public ExecutionResult {
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            errors = errors == null ? Map.of() : Map.copyOf(errors);
        }
    }
}
