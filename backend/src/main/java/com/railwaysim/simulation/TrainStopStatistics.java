package com.railwaysim.simulation;

import java.util.Map;

public record TrainStopStatistics(
    String simulationRunId,
    String trainId,
    String stationId,
    int sampleCount,
    int requiredSampleCount,
    boolean sampleRequirementMet,
    double meanAbsoluteErrorMeters,
    double p95AbsoluteErrorMeters,
    double varianceMetersSquared,
    int successCount,
    double successRate,
    int overrunCount,
    int emergencyBrakeCount,
    Map<String, Integer> reasonDistribution
) {
}
