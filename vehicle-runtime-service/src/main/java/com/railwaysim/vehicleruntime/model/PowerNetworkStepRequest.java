package com.railwaysim.vehicleruntime.model;

import java.util.List;

public record PowerNetworkStepRequest(
    long tick,
    double simulationTimeSeconds,
    double stepSizeSeconds,
    List<PowerNetworkSectionLoadRequest> sectionLoads,
    List<PowerNetworkTrainPosition> trainPositions
) {
    public PowerNetworkStepRequest {
        sectionLoads = sectionLoads == null ? List.of() : List.copyOf(sectionLoads);
        trainPositions = trainPositions == null ? List.of() : List.copyOf(trainPositions);
    }
}
