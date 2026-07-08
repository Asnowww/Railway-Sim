package com.railwaysim.vehicle.external;

public record RtLabTrainOutputPaths(
    int trainNo,
    String trainId,
    String activeCab,
    String direction,
    String acceleration,
    String speed,
    String cumulativeDistance
) {
}
