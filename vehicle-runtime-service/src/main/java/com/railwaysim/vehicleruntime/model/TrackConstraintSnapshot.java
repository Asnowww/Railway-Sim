package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrackConstraintSnapshot(
    String trainId,
    String segmentId,
    double speedLimitMetersPerSecond,
    double gradient,
    double curveRadiusMeters,
    double stationDistanceMeters
) {
}
