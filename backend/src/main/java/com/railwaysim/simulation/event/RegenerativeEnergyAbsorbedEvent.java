package com.railwaysim.simulation.event;

import java.time.Instant;

public record RegenerativeEnergyAbsorbedEvent(
    String sectionId,
    double regenPowerWatts,
    double absorbedPowerWatts,
    double unabsorbedPowerWatts,
    String unabsorbedMode,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "RegenerativeEnergyAbsorbed";
    }
}
