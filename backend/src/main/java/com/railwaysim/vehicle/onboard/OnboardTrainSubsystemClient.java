package com.railwaysim.vehicle.onboard;

import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;

public interface OnboardTrainSubsystemClient {

    OnboardTrainRegistration register(String trainId);

    OnboardTrainControlOutput control(OnboardTrainControlInput input);

    TrainStateReport buildTrainStateReport(
        TrainState train,
        VehiclePhysicsInput input,
        VehiclePhysicsOutput output
    );

    void remove(String trainId);

    void clear();
}
