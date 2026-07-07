package com.railwaysim.simulation.event;

import java.time.Instant;

public record ThirdRailVoltageChangedEvent(
    String sectionId,
    double voltage,
    double current,
    String status,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "ThirdRailVoltageChanged";
    }
}
