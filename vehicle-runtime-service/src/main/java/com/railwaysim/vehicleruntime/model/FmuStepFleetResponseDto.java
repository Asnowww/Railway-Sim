package com.railwaysim.vehicleruntime.model;

import java.util.List;

/** Frozen 9000 -> 9300 batch contract. */
public record FmuStepFleetResponseDto(
    long tick,
    String modelVersion,
    String parameterSetId,
    String traceId,
    List<VehiclePhysicsOutputDto> trainOutputs,
    List<FmuTrainErrorDto> trainErrors
) {
    public FmuStepFleetResponseDto {
        trainOutputs = trainOutputs == null ? List.of() : List.copyOf(trainOutputs);
        trainErrors = trainErrors == null ? List.of() : List.copyOf(trainErrors);
    }
}
