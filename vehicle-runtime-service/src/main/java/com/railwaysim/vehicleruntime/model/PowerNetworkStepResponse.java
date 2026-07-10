package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PowerNetworkStepResponse(
    List<PowerConstraintSnapshot> powerConstraints
) {
    public PowerNetworkStepResponse {
        powerConstraints = powerConstraints == null ? List.of() : List.copyOf(powerConstraints);
    }
}
