package com.railwaysim.vehicleruntime.model;

import java.time.Instant;
import java.util.List;

public record VehicleRuntimeStepResponse(
    long tick,
    Instant sourceTimestamp,
    String dataQuality,
    List<VehiclePhysicsOutputDto> trainOutputs,
    List<TrainStateReportDto> trainReports,
    List<VehicleRuntimeInstanceState> instanceStates,
    List<TrainStateSnapshot> trainStates,
    List<VehicleRuntimeEvent> events
) {
    public VehicleRuntimeStepResponse(
        long tick, Instant sourceTimestamp, String dataQuality,
        List<VehiclePhysicsOutputDto> trainOutputs,
        List<TrainStateReportDto> trainReports,
        List<VehicleRuntimeInstanceState> instanceStates
    ) {
        this(tick, sourceTimestamp, dataQuality, trainOutputs, trainReports, instanceStates, List.of(), List.of());
    }
}
