package com.railwaysim.simulation;

import java.time.Instant;

public record SimulationRunRecord(
    String runId,
    SimulationRunStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant endedAt,
    long lastTick,
    String endReason
) {
}
