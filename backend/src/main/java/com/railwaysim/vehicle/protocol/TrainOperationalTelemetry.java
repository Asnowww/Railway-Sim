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
        requireFiniteNonNegative(speedMetersPerSecond, "speedMetersPerSecond");
        requireFiniteNonNegative(cumulativeDistanceMeters, "cumulativeDistanceMeters");
        direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
        requireFiniteNonNegative(loadMassKg, "loadMassKg");
        requireFiniteNonNegative(faultSpeedLimitMetersPerSecond, "faultSpeedLimitMetersPerSecond");
        if (availableTractionCount < 0 || availableBrakeCount < 0) {
            throw new IllegalArgumentException("available unit counts must be non-negative");
        }
    }

    public String trainId() {
        return "TR-%03d".formatted(trainNo);
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
