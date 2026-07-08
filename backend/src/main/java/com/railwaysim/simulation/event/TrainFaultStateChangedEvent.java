package com.railwaysim.simulation.event;

import java.time.Instant;

public record TrainFaultStateChangedEvent(
    String trainId,
    String faultCode,
    String state,
    String detail,
    Instant occurredAt
) implements DomainEvent {
    @Override
    public String type() {
        return "TrainFaultStateChanged";
    }
}
