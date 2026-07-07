package com.railwaysim.dispatch.monitor;

public record StationInfo(
    String id,
    double positionMeters,
    int platformCapacity
) {
}
