package com.railwaysim.signal.vehicle;

import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.train.TrainState;

public record SignalVehicleCommand(
    String trainId,
    double authorityEndMeters,
    double speedLimitMetersPerSecond,
    boolean tractionCutoff,
    boolean serviceBrakeCommand,
    boolean emergencyBrakeCommand,
    String reason,
    SignalCabDisplayState cabDisplay
) {
    public SignalVehicleCommand(
        String trainId,
        double authorityEndMeters,
        double speedLimitMetersPerSecond,
        boolean tractionCutoff,
        boolean serviceBrakeCommand,
        boolean emergencyBrakeCommand,
        String reason
    ) {
        this(
            trainId,
            authorityEndMeters,
            speedLimitMetersPerSecond,
            tractionCutoff,
            serviceBrakeCommand,
            emergencyBrakeCommand,
            reason,
            SignalCabDisplayState.fromAuthorityOnly(
                trainId,
                speedLimitMetersPerSecond,
                tractionCutoff,
                serviceBrakeCommand,
                emergencyBrakeCommand
            )
        );
    }

    public SignalVehicleCommand {
        authorityEndMeters = Math.max(0, authorityEndMeters);
        speedLimitMetersPerSecond = Math.max(0, speedLimitMetersPerSecond);
        reason = reason == null || reason.isBlank() ? "SIGNAL_AUTHORITY" : reason;
        cabDisplay = cabDisplay == null
            ? SignalCabDisplayState.fromAuthorityOnly(
                trainId,
                speedLimitMetersPerSecond,
                tractionCutoff,
                serviceBrakeCommand,
                emergencyBrakeCommand
            )
            : cabDisplay;
    }

    public static SignalVehicleCommand fromAuthority(MovementAuthority authority) {
        return new SignalVehicleCommand(
            authority.trainId(),
            authority.authorityEndMeters(),
            authority.speedLimitMetersPerSecond(),
            authority.speedLimitMetersPerSecond() <= 0,
            false,
            false,
            authority.reason(),
            SignalCabDisplayState.fromAuthorityOnly(
                authority.trainId(),
                authority.speedLimitMetersPerSecond(),
                authority.speedLimitMetersPerSecond() <= 0,
                false,
                false
            )
        );
    }

    public static SignalVehicleCommand fromAuthority(TrainState train, MovementAuthority authority) {
        if (train == null) {
            throw new IllegalArgumentException("train is required");
        }
        if (!"IN_SERVICE".equals(train.controlSessionState())) {
            return stopCommand(
                train,
                train.positionMeters(),
                false,
                "CONTROL_SESSION_" + train.controlSessionState()
            );
        }
        if (authority == null) {
            return stopCommand(train, train.positionMeters(), true, "NO_MOVEMENT_AUTHORITY");
        }
        if (authority.authorityEndMeters() <= train.positionMeters()) {
            return stopCommand(train, train.positionMeters(), true, "MOVEMENT_AUTHORITY_EXHAUSTED");
        }
        if (authority.speedLimitMetersPerSecond() <= 0) {
            return stopCommand(train, authority.authorityEndMeters(), false, authority.reason());
        }
        double effectiveSpeedLimit = authority.speedLimitMetersPerSecond();
        String reason = authority.reason();
        if (
            train.vehicleFaultSpeedLimitMetersPerSecond() > 0
                && train.vehicleFaultSpeedLimitMetersPerSecond() < effectiveSpeedLimit
        ) {
            effectiveSpeedLimit = train.vehicleFaultSpeedLimitMetersPerSecond();
            reason = "VEHICLE_FAULT_SPEED_LIMIT";
        }
        return new SignalVehicleCommand(
            train.id(),
            authority.authorityEndMeters(),
            effectiveSpeedLimit,
            effectiveSpeedLimit <= 0,
            false,
            false,
            reason,
            SignalCabDisplayState.fromTrain(
                train,
                effectiveSpeedLimit,
                effectiveSpeedLimit <= 0,
                false,
                false,
                reason
            )
        );
    }

    private static SignalVehicleCommand stopCommand(
        TrainState train,
        double authorityEndMeters,
        boolean emergencyBrakeCommand,
        String reason
    ) {
        return new SignalVehicleCommand(
            train.id(),
            authorityEndMeters,
            0,
            true,
            true,
            emergencyBrakeCommand,
            reason,
            SignalCabDisplayState.fromTrain(train, 0, true, true, emergencyBrakeCommand, reason)
        );
    }
}
