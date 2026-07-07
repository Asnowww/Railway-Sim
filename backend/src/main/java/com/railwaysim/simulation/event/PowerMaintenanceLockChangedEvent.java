package com.railwaysim.simulation.event;

import java.time.Instant;

public record PowerMaintenanceLockChangedEvent(
    String sectionId,
    String lockoutState,
    String maintenanceState,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "PowerMaintenanceLockChanged";
    }
}
