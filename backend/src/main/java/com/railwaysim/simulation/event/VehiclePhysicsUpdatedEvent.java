package com.railwaysim.simulation.event;

import java.time.Instant;

public record VehiclePhysicsUpdatedEvent(
    String trainId,
    double positionMeters,
    double speedMetersPerSecond,
    double accelerationMetersPerSecondSquared,
    Instant occurredAt
) implements DomainEvent {

    @Override
    public String type() {
        return "VehiclePhysicsUpdated";
    }
}
