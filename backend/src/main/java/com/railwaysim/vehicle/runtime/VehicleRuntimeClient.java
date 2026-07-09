package com.railwaysim.vehicle.runtime;

import com.railwaysim.train.TrainState;
import java.util.List;

public interface VehicleRuntimeClient {

    VehicleRuntimeHealth health();

    VehicleRuntimeHealth bootstrap(VehicleRuntimeBootstrapRequest request);

    VehicleRuntimeInstanceState registerTrain(TrainState train);

    void removeTrain(String trainId);

    void clear();

    List<VehicleRuntimeInstanceState> instances();

    VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request);
}
