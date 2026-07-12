package com.railwaysim.vehicle.runtime;

import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;

public record VehicleRuntimeStepResult(
    List<VehicleRuntimeTrainStep> trainSteps,
    VehicleRuntimeHealth health,
    List<VehicleRuntimeInstanceState> instanceStates,
    List<TrainState> trainStates,
    List<VehicleRuntimeEvent> events
) {
    public VehicleRuntimeStepResult(
        List<VehicleRuntimeTrainStep> trainSteps,
        VehicleRuntimeHealth health,
        List<VehicleRuntimeInstanceState> instanceStates
    ) {
        this(trainSteps, health, instanceStates, List.of(), List.of());
    }

    public List<VehiclePhysicsOutput> outputs() {
        return trainSteps.stream()
            .map(VehicleRuntimeTrainStep::output)
            .toList();
    }
}
