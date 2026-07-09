package com.railwaysim.dispatch.plan;

import java.time.LocalTime;

public record RunModePeriod(
    String periodType,
    LocalTime startTime,
    LocalTime endTime,
    int departureIntervalSec,
    int defaultDwellTimeSec
) {
}
