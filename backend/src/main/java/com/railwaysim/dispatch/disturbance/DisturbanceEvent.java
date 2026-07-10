package com.railwaysim.dispatch.disturbance;

import java.time.Instant;

public record DisturbanceEvent(
    String id,
    String simulationRunId,
    String trainId,
    String stationId,
    DisturbanceType disturbanceType,
    double deviationValue,
    String status,
    Instant recordedAt,
    Instant resolvedAt,
    String commandId,
    String headwayDirection,
    Double targetHeadwaySec,
    Double actualHeadwaySec,
    Double toleranceSec,
    Double violationSec
) {
    public DisturbanceEvent(
        String id,
        String simulationRunId,
        String trainId,
        String stationId,
        DisturbanceType disturbanceType,
        double deviationValue,
        String status,
        Instant recordedAt,
        Instant resolvedAt,
        String commandId
    ) {
        this(
            id,
            simulationRunId,
            trainId,
            stationId,
            disturbanceType,
            deviationValue,
            status,
            recordedAt,
            resolvedAt,
            commandId,
            null,
            null,
            null,
            null,
            null
        );
    }
}
