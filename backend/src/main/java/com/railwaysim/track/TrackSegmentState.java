package com.railwaysim.track;

public record TrackSegmentState(
    String id,
    double startMeters,
    double endMeters,
    double speedLimitMetersPerSecond,
    TrackOccupancy occupancy
) {
    public TrackSegmentState withOccupancy(TrackOccupancy nextOccupancy) {
        return new TrackSegmentState(id, startMeters, endMeters, speedLimitMetersPerSecond, nextOccupancy);
    }
}
