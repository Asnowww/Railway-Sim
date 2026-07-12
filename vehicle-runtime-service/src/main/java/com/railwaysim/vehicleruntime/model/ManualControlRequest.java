package com.railwaysim.vehicleruntime.model;

public record ManualControlRequest(
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrake,
    double direction,
    Boolean doorOpenRequest,
    Integer timeoutMs
) {
}
