package com.railwaysim.vehicle;

import java.time.Instant;
import java.util.List;

public record StepFleetRequest(
    Instant simTime,
    double deltaSeconds,
    List<VehiclePhysicsInput> trains
) {
}
