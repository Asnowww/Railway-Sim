package com.railwaysim.vehicle.telemetry;

import java.util.List;

public record VehicleTelemetryResponse(boolean accepted, List<VehicleTelemetryResult> results) {
    public VehicleTelemetryResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
