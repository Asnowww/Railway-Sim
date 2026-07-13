package com.railwaysim.api.dto;

import com.railwaysim.vehicle.drivercab.DriverCabDirectionHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabDoorModeSwitch;
import com.railwaysim.vehicle.drivercab.DriverCabMasterHandleState;

/** Structured browser input encoded by 8080 into the 46-byte PLC protocol for 9300. */
public record DriverCabPlcGatewayRequest(
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
    public DriverCabPlcGatewayRequest {
        doorModeSwitchState = doorModeSwitchState == null ? DriverCabDoorModeSwitch.SEMI_AUTOMATIC : doorModeSwitchState;
        directionHandleState = directionHandleState == null ? DriverCabDirectionHandleState.ZERO : directionHandleState;
        masterHandleState = masterHandleState == null ? DriverCabMasterHandleState.ZERO : masterHandleState;
        requirePercent("tractionNotchPercent", tractionNotchPercent);
        requirePercent("brakeNotchPercent", brakeNotchPercent);
    }

    private static void requirePercent(String field, int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be in 0..100");
    }
}
