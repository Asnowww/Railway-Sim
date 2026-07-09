package com.railwaysim.signal.vision;

public record VisionVehicleStateRequest(
    Double speedMetersPerSecond,
    Double accelerationMetersPerSecondSquared,
    Integer accelerationPercent,
    Double headPositionMeters,
    String headSegmentId,
    Integer directionCode,
    String runCondition,
    String headlightState,
    Integer operationCode,
    Integer departureCountdownSeconds
) {
}
