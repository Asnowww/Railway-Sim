package com.railwaysim.dispatch.monitor;

import java.time.Instant;

public record TrainRunProfile(
    String trainId,
    String frontTrainId,
    double positionMeters,
    double speedMps,
    double loadRate,
    String status,
    String currentStationId,
    int dwellElapsedSec,
    int plannedDwellSec,
    int dwellDeviationSec,
    Double headwayActualSec,
    int headwayDeviationSec,
    String headwayState,
    String headwayAction,
    Instant lastDepartureAt
) {
}
