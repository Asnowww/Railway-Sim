package com.railwaysim.dispatch.plan;

public record PlannedStop(
    String stationId,
    int arrivalOffsetSec,
    int departureOffsetSec
) {
}
