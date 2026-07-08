package com.railwaysim.vehicle.external;

public record RtLabTrainInputPaths(
    int trainNo,
    String trainId,
    String segNo,
    String offset,
    String direction,
    String handle,
    String activeCab
) {
}
