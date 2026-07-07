package com.railwaysim.vehicle;

import java.util.List;

public record StepFleetResponse(
    List<VehiclePhysicsOutput> trainOutputs
) {
}
