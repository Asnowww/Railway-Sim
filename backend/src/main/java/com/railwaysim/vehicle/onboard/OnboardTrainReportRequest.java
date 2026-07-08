package com.railwaysim.vehicle.onboard;

import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;

public record OnboardTrainReportRequest(
    TrainState train,
    VehiclePhysicsInput input,
    VehiclePhysicsOutput output
) {
}
