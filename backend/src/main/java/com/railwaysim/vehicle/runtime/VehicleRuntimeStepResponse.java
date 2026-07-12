package com.railwaysim.vehicle.runtime;

import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Instant;
import java.util.List;

public record VehicleRuntimeStepResponse(
    long tick,
    Instant sourceTimestamp,
    String dataQuality,
    List<VehiclePhysicsOutput> trainOutputs,
    List<TrainStateReport> trainReports,
    List<VehicleRuntimeInstanceState> instanceStates,
    List<TrainState> trainStates,
    List<VehicleRuntimeEvent> events
) {
    public VehicleRuntimeStepResponse(
        long tick, Instant sourceTimestamp, String dataQuality,
        List<VehiclePhysicsOutput> trainOutputs,
        List<TrainStateReport> trainReports,
        List<VehicleRuntimeInstanceState> instanceStates
    ) {
        this(tick, sourceTimestamp, dataQuality, trainOutputs, trainReports, instanceStates, List.of(), List.of());
    }
}
