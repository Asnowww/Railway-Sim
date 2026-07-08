package com.railwaysim.simulation.event;

import java.time.Instant;

public record RegenerativePowerGeneratedEvent(
    String trainId,
    double regenPowerWatts,
    double energyRegeneratedKwh,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "RegenerativePowerGenerated";
    }
}
