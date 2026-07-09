package com.railwaysim.dispatch.monitor;

import java.time.Instant;
import java.util.Optional;

public record TrainStationEvent(
    String simulationRunId,
    String trainId,
    String lineId,
    String stationId,
    EventType eventType,
    Instant simulatedAt,
    Optional<Instant> plannedTime,
    int delaySec
) {
    public enum EventType {
        ARRIVAL,
        DEPARTURE
    }
}
