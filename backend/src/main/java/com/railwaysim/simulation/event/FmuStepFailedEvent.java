package com.railwaysim.simulation.event;

import java.time.Instant;

public record FmuStepFailedEvent(
    String trainId,
    String detail,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "FmuStepFailed";
    }
}
