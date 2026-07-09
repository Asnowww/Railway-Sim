package com.railwaysim.signal.vision;

record VisionTrainProjection(
    String trainId,
    double headPositionMeters,
    String headSegmentId,
    int segmentNumber,
    double speedMetersPerSecond,
    double accelerationMetersPerSecondSquared,
    int accelerationPercent,
    int directionCode,
    VisionRunCondition runCondition,
    VisionHeadlightState headlightState,
    int operationCode,
    int departureCountdownSeconds
) {
}
