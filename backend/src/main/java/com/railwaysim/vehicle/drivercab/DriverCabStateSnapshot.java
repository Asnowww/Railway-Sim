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

    public static DriverCabStateSnapshot fromInput(DriverCabPlcInputPacket packet, Instant updatedAt) {
        return new DriverCabStateSnapshot(
            packet.doorModeSwitchState(),
            packet.atoStartFlag(),
            packet.modeUpgradeConfirmFlag(),
            packet.modeDowngradeConfirmFlag(),
            packet.automaticTurnbackFlag(),
            packet.directionHandleState(),
            packet.masterHandleState(),
            packet.keySwitchLocked(),
            packet.tractionNotchPercent(),
            packet.brakeNotchPercent(),
            updatedAt
        );
    }

    private static void requirePercent(String field, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be in 0..100: " + value);
        }
    }
}
