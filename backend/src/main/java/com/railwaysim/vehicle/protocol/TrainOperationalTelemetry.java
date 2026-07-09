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
        speedMetersPerSecond = finiteNonNegative(speedMetersPerSecond);
        cumulativeDistanceMeters = finiteNonNegative(cumulativeDistanceMeters);
        direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
        loadMassKg = finiteNonNegative(loadMassKg);
        faultSpeedLimitMetersPerSecond = finiteNonNegative(faultSpeedLimitMetersPerSecond);
        availableTractionCount = Math.max(0, availableTractionCount);
        availableBrakeCount = Math.max(0, availableBrakeCount);
    }

    public String trainId() {
        return "TR-%03d".formatted(trainNo);
    }

    private static double finiteNonNegative(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, value);
    }
}
