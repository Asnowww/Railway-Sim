package com.railwaysim.dispatch.plan;

import java.time.Instant;

public record CurrentRunPlan(
    String planId,
    String lineId,
    String periodType,
    int departureIntervalSec,
    int defaultDwellTimeSec,
    Instant resolvedAt
) {
}
