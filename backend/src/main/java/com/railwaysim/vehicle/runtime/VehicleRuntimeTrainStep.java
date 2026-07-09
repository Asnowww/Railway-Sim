package com.railwaysim.vehicle.runtime;

import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsOutput;

public record VehicleRuntimeTrainStep(
    String trainId,
    VehiclePhysicsOutput output,
    TrainStateReport report
) {
}
