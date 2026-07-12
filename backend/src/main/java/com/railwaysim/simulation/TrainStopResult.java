package com.railwaysim.simulation;

import java.time.Instant;
import java.util.List;

public record TrainStopResult(
    String resultId,
    String simulationRunId,
    long stableAtTick,
    String trainId,
    String stationId,
    String platformId,
    String targetSource,
    double targetPositionMeters,
    long targetValidFromTick,
    boolean targetOverriddenByMovementAuthority,
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
    String finalControlStage,
    List<String> controlStageHistory,
    String controlMode,
    String parameterVersion,
    Instant recordedAt
) {
    public TrainStopResult {
        controlStageHistory = controlStageHistory == null ? List.of() : List.copyOf(controlStageHistory);
    }
}
