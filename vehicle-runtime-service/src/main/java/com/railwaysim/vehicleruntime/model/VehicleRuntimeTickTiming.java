package com.railwaysim.vehicleruntime.model;

/** Stage timing for one authoritative 9300 fleet tick. */
public record VehicleRuntimeTickTiming(
    long tick,
    int trainCount,
    double powerConstraintQueryMillis,
    double controlPreparationMillis,
    double fmuFleetStepMillis,
    double stateApplyMillis,
    double powerNetworkStepMillis,
    double totalMillis
) {
    public static VehicleRuntimeTickTiming idle() {
        return new VehicleRuntimeTickTiming(-1, 0, 0, 0, 0, 0, 0, 0);
    }
}
