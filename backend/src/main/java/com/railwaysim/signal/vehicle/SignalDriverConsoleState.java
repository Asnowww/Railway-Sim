package com.railwaysim.signal.vehicle;

import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.drivercab.DriverCabDirectionHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabDoorModeSwitch;
import com.railwaysim.vehicle.drivercab.DriverCabMasterHandleState;
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
        if (train.driverCabState() != null) {
            return fromDriverCabState(train.id(), train.driverCabState());
        }
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

    private static SignalDriverConsoleState fromDriverCabState(String trainId, DriverCabStateSnapshot state) {
        return new SignalDriverConsoleState(
            trainId,
            mapDoorMode(state.doorModeSwitchState()),
            state.atoStartFlag(),
            state.modeUpgradeConfirmFlag(),
            state.modeDowngradeConfirmFlag(),
            state.automaticTurnbackFlag(),
            mapDirectionHandle(state.directionHandleState()),
            mapMasterHandle(state.masterHandleState())
        );
    }

    private static DoorModeSwitch mapDoorMode(DriverCabDoorModeSwitch state) {
        return switch (state) {
            case MANUAL -> DoorModeSwitch.MANUAL;
            case AUTOMATIC -> DoorModeSwitch.AUTOMATIC;
            default -> DoorModeSwitch.SEMI_AUTOMATIC;
        };
    }

    private static DirectionHandleState mapDirectionHandle(DriverCabDirectionHandleState state) {
        return switch (state) {
            case FORWARD -> DirectionHandleState.FORWARD;
            case BACKWARD -> DirectionHandleState.BACKWARD;
            default -> DirectionHandleState.ZERO;
        };
    }

    private static MasterHandleState mapMasterHandle(DriverCabMasterHandleState state) {
        return switch (state) {
            case TRACTION -> MasterHandleState.TRACTION;
            case BRAKE -> MasterHandleState.BRAKE;
            case FAST_BRAKE -> MasterHandleState.FAST_BRAKE;
            default -> MasterHandleState.ZERO;
        };
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
