package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MovementAuthoritySnapshot(
    String trainId,
    double authorityEndMeters,
    double speedLimitMetersPerSecond,
    String reason
) {
}
