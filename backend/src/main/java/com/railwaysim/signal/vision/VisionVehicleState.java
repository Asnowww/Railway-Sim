package com.railwaysim.signal.vision;

import java.time.Instant;

public record VisionVehicleState(
    String trainId,
    Double speedMetersPerSecond,
    Double accelerationMetersPerSecondSquared,
    Integer accelerationPercent,
    Double headPositionMeters,
    String headSegmentId,
    Integer directionCode,
    VisionRunCondition runCondition,
    VisionHeadlightState headlightState,
    Integer operationCode,
    Integer departureCountdownSeconds,
    Instant updatedAt
) {
    public static VisionVehicleState from(String trainId, VisionVehicleStateRequest request) {
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("vision vehicle state request is required");
        }
        return new VisionVehicleState(
            trainId.trim(),
            finiteOrNull(request.speedMetersPerSecond()),
            finiteOrNull(request.accelerationMetersPerSecondSquared()),
            clampOrNull(request.accelerationPercent(), -100, 100),
            finiteOrNull(request.headPositionMeters()),
            blankToNull(request.headSegmentId()),
            normalizeDirection(request.directionCode()),
            VisionRunCondition.from(request.runCondition()),
            VisionHeadlightState.from(request.headlightState()),
            clampOrNull(request.operationCode(), 0, 255),
            clampOrNull(request.departureCountdownSeconds(), 0, 0xffff),
            Instant.now()
        );
    }

    private static Double finiteOrNull(Double value) {
        return value == null || !Double.isFinite(value) ? null : value;
    }

    private static Integer clampOrNull(Integer value, int min, int max) {
        if (value == null) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static Integer normalizeDirection(Integer value) {
        if (value == null) {
            return null;
        }
        return value < 0 ? -1 : 1;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
