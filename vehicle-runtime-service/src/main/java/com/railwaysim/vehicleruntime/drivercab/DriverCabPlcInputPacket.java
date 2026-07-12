package com.railwaysim.vehicleruntime.drivercab;

import java.time.Instant;

public record DriverCabPlcInputPacket(
    boolean highVoltageClosedIndicator,
    boolean doorsClosedLockedIndicator,
    boolean networkFaultIndicator,
    boolean automaticTurnbackAvailable,
    boolean atoModeAvailable,
    boolean atoModeActive,
    boolean automaticTurnbackActive,
    boolean emergencyBrakeButtonLocked,
    boolean openLeftDoorFlag,
    boolean openRightDoorFlag,
    boolean closeLeftDoorFlag,
    boolean closeRightDoorFlag,
    DriverCabDoorModeSwitch doorModeSwitchState,
    boolean modeUpgradeConfirmFlag,
    boolean modeDowngradeConfirmFlag,
    boolean automaticTurnbackFlag,
    boolean atoStartFlag,
    boolean keySwitchLocked,
    DriverCabDirectionHandleState directionHandleState,
    DriverCabMasterHandleState masterHandleState,
    int tractionNotchPercent,
    int brakeNotchPercent
) {
    public DriverCabPlcInputPacket {
        doorModeSwitchState = doorModeSwitchState == null ? DriverCabDoorModeSwitch.SEMI_AUTOMATIC : doorModeSwitchState;
        directionHandleState = directionHandleState == null ? DriverCabDirectionHandleState.ZERO : directionHandleState;
        masterHandleState = masterHandleState == null ? DriverCabMasterHandleState.ZERO : masterHandleState;
        requirePercent("tractionNotchPercent", tractionNotchPercent);
        requirePercent("brakeNotchPercent", brakeNotchPercent);
    }

    public boolean emergencyBrakeRequested() {
        return emergencyBrakeButtonLocked || masterHandleState == DriverCabMasterHandleState.FAST_BRAKE;
    }

    public boolean openDoorRequested() { return openLeftDoorFlag || openRightDoorFlag; }
    public boolean closeDoorRequested() { return closeLeftDoorFlag || closeRightDoorFlag; }

    public static DriverCabPlcInputPacket neutral() {
        return new DriverCabPlcInputPacket(true, true, false, false, true, false, false, false,
            false, false, false, false, DriverCabDoorModeSwitch.AUTOMATIC, false, false, false,
            false, true, DriverCabDirectionHandleState.FORWARD, DriverCabMasterHandleState.ZERO, 0, 0);
    }

    private static void requirePercent(String field, int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be in 0..100: " + value);
    }
}
