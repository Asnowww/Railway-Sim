package com.railwaysim.simulation;

import java.time.Instant;

public record TickContext(
    long tick,
    long stepMillis,
    double deltaSeconds,
    Instant simulatedTime
) {
}

