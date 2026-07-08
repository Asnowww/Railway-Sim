package com.railwaysim.vehicle;

public record TrainStateReport(
    String trainId,
    String operationMode,
    boolean doorClosed,
    String doorState,
    String tractionState,
    String brakeState,
    String currentCollectionStatus,
    boolean tractionAvailable,
    boolean brakeAvailable,
    String selfCheckStatus,
    int faultLevel,
    String availableOperationMode,
    String dataQuality,
    String dynamicsState,
    String dynamicsConstraintReason,
    double speedLimitMetersPerSecond,
    double movementAuthorityDistanceMeters,
    double stationDistanceMeters,
    double stoppingDistanceMeters,
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrakeCommand,
    double railVoltage,
    double powerAvailableWatts,
    String faultCode
) {
}
