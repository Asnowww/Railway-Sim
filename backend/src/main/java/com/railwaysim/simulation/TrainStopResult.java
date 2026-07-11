package com.railwaysim.simulation;

import java.time.Instant;

public record TrainStopResult(
    String resultId,
    String simulationRunId,
    long stableAtTick,
    String trainId,
    String stationId,
    String platformId,
    String targetSource,
    double targetPositionMeters,
    double actualPositionMeters,
    double signedErrorMeters,
    double absoluteErrorMeters,
    boolean overrun,
    boolean success,
    String reasonCode,
    double maximumDecelerationMetersPerSecondSquared,
    double maximumJerkMetersPerSecondCubed,
    int brakeTransitionCount,
    boolean emergencyBrake,
    String controlMode,
    String parameterVersion,
    Instant recordedAt
) {
}
