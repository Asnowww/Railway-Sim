package com.railwaysim.dispatch.monitor;

import java.time.Instant;

public record StationHeadwayObservation(
    String stationId,
    String direction,
    String trainId,
    String frontTrainId,
    Instant frontDepartureAt,
    Instant departureAt,
    int targetHeadwaySec,
    double actualHeadwaySec,
    double headwayErrorSec,
    String state,
    String regulationAction
) {
}
