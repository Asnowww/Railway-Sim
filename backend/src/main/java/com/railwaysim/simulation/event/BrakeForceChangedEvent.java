package com.railwaysim.simulation.event;

import java.time.Instant;

public record BrakeForceChangedEvent(
    String trainId,
    double brakeForceNewtons,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "BrakeForceChanged";
    }
}
