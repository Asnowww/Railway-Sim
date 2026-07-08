package com.railwaysim.vehicle.protocol;

import com.railwaysim.vehicle.external.ExternalTrainDirection;

public record TrainOperationalTelemetry(
    int trainNo,
    double speedMetersPerSecond,
    double cumulativeDistanceMeters,
    ExternalTrainDirection direction,
    double loadMassKg,
    double faultSpeedLimitMetersPerSecond,
    boolean emergencyBrakeApplied,
    int availableTractionCount,
    int availableBrakeCount
) {
    public TrainOperationalTelemetry {
        if (trainNo < 1) {
            throw new IllegalArgumentException("trainNo must be positive");
        }
        direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
        loadMassKg = Math.max(0, loadMassKg);
        faultSpeedLimitMetersPerSecond = Math.max(0, faultSpeedLimitMetersPerSecond);
        availableTractionCount = Math.max(0, availableTractionCount);
        availableBrakeCount = Math.max(0, availableBrakeCount);
    }

    public String trainId() {
        return "TR-%03d".formatted(trainNo);
    }
}
