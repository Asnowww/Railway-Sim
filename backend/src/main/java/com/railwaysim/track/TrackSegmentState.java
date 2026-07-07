package com.railwaysim.track;

public record TrackSegmentState(
    String id,
    double startMeters,
    double endMeters,
    double speedLimitMetersPerSecond,
    TrackOccupancy occupancy
) {
}

