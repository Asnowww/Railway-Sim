package com.railwaysim.signal.vehicle;

import com.railwaysim.train.TrainState;

public record SignalCabDisplayState(
    String trainId,
    DrivingMode currentDrivingMode,
    DrivingMode maximumAvailableDrivingMode,
    DoorEnableState doorEnable,
    DoorControlMode doorControlMode,
    TractionBrakeInfo tractionBrakeInfo,
    DepartureInfo departureInfo,
    TurnbackInfo turnbackInfo,
    double speedLimitMetersPerSecond,
    boolean emergencyBrake,
    double distanceToNextStationMeters
) {
    private static final double STATION_STOP_WINDOW_METERS = 8.0;
    private static final double NO_STATION_DISTANCE_METERS = 1_000_000.0;

    public static SignalCabDisplayState fromAuthorityOnly(
        String trainId,
        double speedLimitMetersPerSecond,
        boolean tractionCutoff,
        boolean serviceBrake,
        boolean emergencyBrake
    ) {
        return new SignalCabDisplayState(
            trainId,
            emergencyBrake ? DrivingMode.RM : DrivingMode.ATO,
            emergencyBrake ? DrivingMode.RM : DrivingMode.ATO,
            DoorEnableState.none(),
            emergencyBrake ? DoorControlMode.MANUAL : DoorControlMode.AUTOMATIC,
            resolveTractionBrakeInfo(null, tractionCutoff, serviceBrake, emergencyBrake),
            emergencyBrake || tractionCutoff ? DepartureInfo.HOLD : DepartureInfo.DEPART,
            TurnbackInfo.INACTIVE,
            bounded(speedLimitMetersPerSecond),
            emergencyBrake,
            NO_STATION_DISTANCE_METERS
        );
    }

    public static SignalCabDisplayState fromTrain(
        TrainState train,
        double speedLimitMetersPerSecond,
        boolean tractionCutoff,
        boolean serviceBrake,
        boolean emergencyBrake,
        String reason
    ) {
        DoorControlMode doorControlMode = resolveDoorControlMode(train, emergencyBrake);
        return new SignalCabDisplayState(
            train.id(),
            resolveCurrentDrivingMode(train, emergencyBrake),
            resolveMaximumAvailableDrivingMode(train, emergencyBrake),
            resolveDoorEnable(train, doorControlMode, emergencyBrake),
            doorControlMode,
            resolveTractionBrakeInfo(train, tractionCutoff, serviceBrake, emergencyBrake),
            resolveDepartureInfo(train, tractionCutoff, emergencyBrake),
            resolveTurnbackInfo(train, reason),
            bounded(speedLimitMetersPerSecond),
            emergencyBrake || "EMERGENCY".equals(train.brakeState()),
            bounded(train.stationDistanceMeters())
        );
    }

    private static DrivingMode resolveCurrentDrivingMode(TrainState train, boolean emergencyBrake) {
        String operationMode = nullTo(train.operationMode(), "ATO");
        if (emergencyBrake || "ATP_BRAKE".equals(operationMode)) {
            return DrivingMode.RM;
        }
        if ("AR".equals(operationMode)) {
            return DrivingMode.AR;
        }
        if ("DTO".equals(operationMode)) {
            return DrivingMode.DTO;
        }
        if ("DEGRADED".equals(operationMode) || "DEGRADED".equals(train.availableOperationMode())) {
            return DrivingMode.SM;
        }
        return DrivingMode.ATO;
    }

    private static DrivingMode resolveMaximumAvailableDrivingMode(TrainState train, boolean emergencyBrake) {
        if (
            emergencyBrake
                || train.faultLevel() >= 3
                || "NO_DEPARTURE".equals(train.availableOperationMode())
                || !"IN_SERVICE".equals(train.controlSessionState())
        ) {
            return DrivingMode.RM;
        }
        if ("DEGRADED".equals(train.availableOperationMode()) || "DEGRADED".equals(train.operationMode())) {
            return DrivingMode.SM;
        }
        if ("AR".equals(train.operationMode())) {
            return DrivingMode.AR;
        }
        return DrivingMode.ATO;
    }

    private static DoorControlMode resolveDoorControlMode(TrainState train, boolean emergencyBrake) {
        if (emergencyBrake || "ATP_BRAKE".equals(train.operationMode())) {
            return DoorControlMode.MANUAL;
        }
        if ("DEGRADED".equals(train.availableOperationMode()) || "STATION_CONTROL".equals(train.operationMode())) {
            return DoorControlMode.SEMI_AUTOMATIC;
        }
        return "ATO".equals(train.operationMode()) || "AR".equals(train.operationMode())
            ? DoorControlMode.AUTOMATIC
            : DoorControlMode.MANUAL;
    }

    private static DoorEnableState resolveDoorEnable(
        TrainState train,
        DoorControlMode doorControlMode,
        boolean emergencyBrake
    ) {
        if (emergencyBrake || !atStationStop(train)) {
            return DoorEnableState.none();
        }
        return new DoorEnableState(
            DoorSide.BOTH,
            doorControlMode != DoorControlMode.MANUAL,
            true
        );
    }

    private static boolean atStationStop(TrainState train) {
        return train.zeroSpeed()
            && (
                "DWELLING".equals(train.status())
                    || "STATION_STOPPED".equals(train.dynamicsState())
                    || (train.currentStationId() != null && !train.currentStationId().isBlank())
                    || train.stationDistanceMeters() <= STATION_STOP_WINDOW_METERS
            );
    }

    private static TractionBrakeInfo resolveTractionBrakeInfo(
        TrainState train,
        boolean tractionCutoff,
        boolean serviceBrake,
        boolean emergencyBrake
    ) {
        if (emergencyBrake || (train != null && "EMERGENCY".equals(train.brakeState()))) {
            return TractionBrakeInfo.EMERGENCY_BRAKING;
        }
        if (
            serviceBrake
                || (train != null && ("SERVICE".equals(train.brakeState()) || "REGENERATIVE".equals(train.brakeState())))
                || (train != null && train.brakeForceNewtons() > 0)
        ) {
            return TractionBrakeInfo.BRAKING;
        }
        if (
            !tractionCutoff
                && train != null
                && ("APPLYING".equals(train.tractionState()) || "DERATED".equals(train.tractionState()) || train.tractionForceNewtons() > 0)
        ) {
            return TractionBrakeInfo.TRACTION;
        }
        return TractionBrakeInfo.COASTING;
    }

    private static DepartureInfo resolveDepartureInfo(
        TrainState train,
        boolean tractionCutoff,
        boolean emergencyBrake
    ) {
        if (
            emergencyBrake
                || tractionCutoff
                || !"IN_SERVICE".equals(train.controlSessionState())
                || !"CLOSED_LOCKED".equals(train.doorState())
                || !train.tractionAvailable()
                || !train.brakeAvailable()
                || "FAIL".equals(train.selfCheckStatus())
                || "NO_DEPARTURE".equals(train.availableOperationMode())
        ) {
            return DepartureInfo.HOLD;
        }
        return DepartureInfo.DEPART;
    }

    private static TurnbackInfo resolveTurnbackInfo(TrainState train, String reason) {
        if ("AR".equals(train.operationMode())) {
            return TurnbackInfo.ACTIVE;
        }
        String value = nullTo(reason, "") + " " + nullTo(train.dynamicsConstraintReason(), "");
        return value.contains("TURNBACK") || value.contains("折返")
            ? TurnbackInfo.AVAILABLE
            : TurnbackInfo.INACTIVE;
    }

    private static double bounded(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DoorEnableState(
        DoorSide side,
        boolean automaticOpenAllowed,
        boolean manualOpenAllowed
    ) {
        public static DoorEnableState none() {
            return new DoorEnableState(DoorSide.NONE, false, false);
        }
    }

    public enum DrivingMode {
        DTO,
        ATO,
        AR,
        SM,
        RM
    }

    public enum DoorSide {
        NONE,
        LEFT,
        RIGHT,
        BOTH,
        BOTH_LEFT_FIRST,
        BOTH_RIGHT_FIRST
    }

    public enum DoorControlMode {
        AUTOMATIC,
        SEMI_AUTOMATIC,
        MANUAL
    }

    public enum TractionBrakeInfo {
        COASTING,
        TRACTION,
        BRAKING,
        EMERGENCY_BRAKING
    }

    public enum DepartureInfo {
        HOLD,
        DEPART
    }

    public enum TurnbackInfo {
        INACTIVE,
        AVAILABLE,
        ACTIVE
    }
}
