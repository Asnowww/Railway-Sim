package com.railwaysim.track;

public record TrackConstraint(
    String trainId,
    String segmentId,
    double speedLimitMetersPerSecond,
    double gradient,
    double curveRadiusMeters,
    double stationDistanceMeters
) {
}
