package com.railwaysim.signal.vehicle;

import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.drivercab.DriverCabStateSnapshot;

public record SignalDriverConsoleState(
    String trainId,
    DoorModeSwitch doorModeSwitchState,
    boolean atoStartFlag,
    boolean modeUpgradeConfirmFlag,
    boolean modeDowngradeConfirmFlag,
    boolean automaticTurnbackFlag,
    DirectionHandleState directionHandleState,
    MasterHandleState masterHandleState
) {
    public static SignalDriverConsoleState from(TrainState train) {
        DriverCabStateSnapshot driverCabState = train.driverCabState();
        if (driverCabState != null) {
            return new SignalDriverConsoleState(
                train.id(),
                DoorModeSwitch.valueOf(driverCabState.doorModeSwitchState().name()),
                driverCabState.atoStartFlag(),
                driverCabState.modeUpgradeConfirmFlag(),
                driverCabState.modeDowngradeConfirmFlag(),
                driverCabState.automaticTurnbackFlag(),
                DirectionHandleState.valueOf(driverCabState.directionHandleState().name()),
                MasterHandleState.valueOf(driverCabState.masterHandleState().name())
            );
        }
        // 从未收到司机台 PLC 输入时，从运行状态推导默认值
        return new SignalDriverConsoleState(
            train.id(),
            resolveDoorMode(train),
            "ATO".equals(train.operationMode()) && "IN_SERVICE".equals(train.controlSessionState()),
            false,
            false,
            "AR".equals(train.operationMode()),
            resolveDirectionHandle(train),
            resolveMasterHandle(train)
        );
    }

    private static DoorModeSwitch resolveDoorMode(TrainState train) {
        if ("ATP_BRAKE".equals(train.operationMode()) || "NO_DEPARTURE".equals(train.availableOperationMode())) {
            return DoorModeSwitch.MANUAL;
        }
        if ("DEGRADED".equals(train.availableOperationMode()) || "STATION_CONTROL".equals(train.operationMode())) {
            return DoorModeSwitch.SEMI_AUTOMATIC;
        }
        return DoorModeSwitch.AUTOMATIC;
    }

    private static DirectionHandleState resolveDirectionHandle(TrainState train) {
        String direction = train.direction() == null ? "" : train.direction();
        if ("UP".equals(direction) || "DOWN".equals(direction)) {
            return DirectionHandleState.FORWARD;
        }
        return DirectionHandleState.ZERO;
    }

    private static MasterHandleState resolveMasterHandle(TrainState train) {
        if ("EMERGENCY".equals(train.brakeState())) {
            return MasterHandleState.FAST_BRAKE;
        }
        if ("SERVICE".equals(train.brakeState()) || "REGENERATIVE".equals(train.brakeState()) || train.brakeForceNewtons() > 0) {
            return MasterHandleState.BRAKE;
        }
        if ("APPLYING".equals(train.tractionState()) || "DERATED".equals(train.tractionState()) || train.tractionForceNewtons() > 0) {
            return MasterHandleState.TRACTION;
        }
        return MasterHandleState.ZERO;
    }

    public enum DoorModeSwitch {
        SEMI_AUTOMATIC,
        MANUAL,
        AUTOMATIC
    }

    public enum DirectionHandleState {
        ZERO,
        FORWARD,
        BACKWARD
    }

    public enum MasterHandleState {
        ZERO,
        TRACTION,
        BRAKE,
        FAST_BRAKE
    }
}
