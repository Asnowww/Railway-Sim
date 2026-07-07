package com.railwaysim.simulation.event;

import java.time.Instant;

public interface DomainEvent {

    String type();

    Instant occurredAt();
}

