package com.railwaysim.dispatch.monitor;

import java.time.Instant;

public record TrainRunProfile(
    String trainId,
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
    Instant lastDepartureAt
) {
}
