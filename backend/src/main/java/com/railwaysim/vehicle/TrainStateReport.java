package com.railwaysim.vehicle;

public record TrainStateReport(
    String trainId,
    String operationMode,
    boolean doorClosed,
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrakeCommand,
    double railVoltage,
    double powerAvailableWatts,
    String faultCode
) {
}
