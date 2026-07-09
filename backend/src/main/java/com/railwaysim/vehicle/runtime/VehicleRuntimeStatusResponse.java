package com.railwaysim.vehicle.runtime;

import java.util.List;

public record VehicleRuntimeStatusResponse(
    VehicleRuntimeHealth health,
    List<VehicleRuntimeInstanceState> instances
) {
}
