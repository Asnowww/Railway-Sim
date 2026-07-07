package com.railwaysim.simulation.event;

import java.time.Instant;

public record TractionPowerChangedEvent(
    String trainId,
    double tractionPowerWatts,
    double railCurrentAmps,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "TractionPowerChanged";
    }
}
