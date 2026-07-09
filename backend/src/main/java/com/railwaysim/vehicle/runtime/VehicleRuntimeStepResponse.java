package com.railwaysim.vehicle.runtime;

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
    List<VehicleRuntimeInstanceState> instanceStates
) {
}
