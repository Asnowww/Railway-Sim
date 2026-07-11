package com.railwaysim.vehicleruntime.model;

import java.util.List;

/** Frozen 9300 -> 9000 batch contract. The executor is introduced in WP5. */
public record FmuStepFleetRequestDto(
    long tick,
    double simulationTimeSeconds,
    double stepSizeSeconds,
    String modelVersion,
    String parameterSetId,
    String traceId,
    List<VehiclePhysicsInputDto> trains
) {
    public FmuStepFleetRequestDto {
        trains = trains == null ? List.of() : List.copyOf(trains);
    }
}
