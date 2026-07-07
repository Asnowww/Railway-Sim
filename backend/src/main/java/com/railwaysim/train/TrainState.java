package com.railwaysim.train;

public record TrainState(
    String id,
    String routeId,
    double positionMeters,
    double speedMetersPerSecond,
    double lengthMeters,
    double loadRate,
    String status,
    String currentStationId,
    int dwellElapsedSeconds,
    String lastDepartureAt
) {
}
