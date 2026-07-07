package com.railwaysim.simulation.event;

import java.time.Instant;

public record PowerFaultStateChangedEvent(
    String sectionId,
    String faultType,
    String state,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "PowerFaultStateChanged";
    }
}
