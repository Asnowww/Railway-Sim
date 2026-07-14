package com.railwaysim.simulation;

/**
 * One completed central tick split into the stages that matter to the
 * 8080 -> 9300 -> 9000/9200 closed loop.  Values are measured inside 8080 and
 * therefore exclude HTTP response serialization back to the benchmark client.
 */
public record SimulationTickTiming(
    long tick,
    int trainCount,
    boolean externalPowerAuthority,
    double trainStateAndTrackConstraintMillis,
    double preliminarySignalAndDispatchMillis,
    double commandAndInterlockingMillis,
    double finalConstraintAndPowerBootstrapMillis,
    double constraintPreparationMillis,
    double vehicleRuntimeMillis,
    double centralVehiclePostProcessingMillis,
    double authoritativePowerSnapshotMillis,
    double centralPostProcessingMillis,
    double serviceHealthMillis,
    double alarmProjectionMillis,
    double alarmReconciliationMillis,
    double snapshotBuildMillis,
    double webSocketPushMillis,
    double totalMillis
) {
    public static SimulationTickTiming idle() {
        return new SimulationTickTiming(
            0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
