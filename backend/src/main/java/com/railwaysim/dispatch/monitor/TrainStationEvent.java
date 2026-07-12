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
    int delaySec,
    String serviceId,
    String circulationId,
    String direction
) {
    public TrainStationEvent(
        String simulationRunId,
        String trainId,
        String lineId,
        String stationId,
        EventType eventType,
        Instant simulatedAt,
        Optional<Instant> plannedTime,
        int delaySec
    ) {
        this(simulationRunId, trainId, lineId, stationId, eventType, simulatedAt, plannedTime, delaySec,
            null, null, null);
    }

    public enum EventType {
        ARRIVAL,
        DEPARTURE
    }
}
