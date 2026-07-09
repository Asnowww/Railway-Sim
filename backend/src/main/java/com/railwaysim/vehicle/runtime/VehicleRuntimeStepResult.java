package com.railwaysim.vehicle.runtime;

import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;

public record VehicleRuntimeStepResult(
    List<VehicleRuntimeTrainStep> trainSteps,
    VehicleRuntimeHealth health,
    List<VehicleRuntimeInstanceState> instanceStates
) {
    public List<VehiclePhysicsOutput> outputs() {
        return trainSteps.stream()
            .map(VehicleRuntimeTrainStep::output)
            .toList();
    }
}
