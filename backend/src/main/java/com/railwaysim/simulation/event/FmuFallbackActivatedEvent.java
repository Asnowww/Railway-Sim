package com.railwaysim.simulation.event;

import java.time.Instant;

public record FmuFallbackActivatedEvent(
    String scope,
    String detail,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "FmuFallbackActivated";
    }
}
