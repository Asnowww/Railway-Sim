package com.railwaysim.vehicleruntime.model;

import java.time.Instant;
import java.util.List;

public record VehicleTelemetryRequest(
    String simulationRunId,
    String sourceId,
    Instant sourceTimestamp,
    long sequence,
    List<VehicleTelemetrySample> telemetries
) {
    public VehicleTelemetryRequest {
        telemetries = telemetries == null ? List.of() : List.copyOf(telemetries);
    }
}
