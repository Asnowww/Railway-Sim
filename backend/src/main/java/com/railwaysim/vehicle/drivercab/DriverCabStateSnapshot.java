package com.railwaysim.vehicle.drivercab;

import java.time.Instant;

public record DriverCabStateSnapshot(
    DriverCabDoorModeSwitch doorModeSwitchState,
    boolean atoStartFlag,
    boolean modeUpgradeConfirmFlag,
    boolean modeDowngradeConfirmFlag,
    boolean automaticTurnbackFlag,
    DriverCabDirectionHandleState directionHandleState,
    DriverCabMasterHandleState masterHandleState,
    boolean keySwitchLocked,
    int tractionNotchPercent,
    int brakeNotchPercent,
    Instant updatedAt
) {
    public DriverCabStateSnapshot {
        doorModeSwitchState = doorModeSwitchState == null ? DriverCabDoorModeSwitch.SEMI_AUTOMATIC : doorModeSwitchState;
        directionHandleState = directionHandleState == null ? DriverCabDirectionHandleState.ZERO : directionHandleState;
        masterHandleState = masterHandleState == null ? DriverCabMasterHandleState.ZERO : masterHandleState;
        requirePercent("tractionNotchPercent", tractionNotchPercent);
        requirePercent("brakeNotchPercent", brakeNotchPercent);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    private static void requirePercent(String field, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be in 0..100: " + value);
        }
    }
}
