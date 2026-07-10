package com.railwaysim.vehicleruntime.model;

import java.util.List;

public record PowerNetworkConstraintQueryRequest(
    List<PowerNetworkTrainPosition> trainPositions
) {
    public PowerNetworkConstraintQueryRequest {
        trainPositions = trainPositions == null ? List.of() : List.copyOf(trainPositions);
    }
}
