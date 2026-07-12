package com.railwaysim.simulation.event;

import com.railwaysim.simulation.StoppingTarget;
import java.time.Instant;

public record StoppingTargetOverriddenEvent(
    String simulationRunId,
    long tick,
    String trainId,
    StoppingTarget previousTarget,
    StoppingTarget selectedTarget,
    String reasonCode,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "StoppingTargetOverridden";
    }
}
