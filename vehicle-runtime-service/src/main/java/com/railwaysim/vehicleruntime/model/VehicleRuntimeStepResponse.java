package com.railwaysim.vehicleruntime.model;

import java.time.Instant;
import java.util.List;

public record VehicleRuntimeStepResponse(
    long tick,
    Instant sourceTimestamp,
    String dataQuality,
    List<VehiclePhysicsOutputDto> trainOutputs,
    List<TrainStateReportDto> trainReports,
    List<VehicleRuntimeInstanceState> instanceStates
) {
}
