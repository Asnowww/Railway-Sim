package com.railwaysim.simulation.event;

import java.time.Instant;

public record PowerLimitTriggeredEvent(
    String sectionId,
    double voltage,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "PowerLimitTriggered";
    }
}
