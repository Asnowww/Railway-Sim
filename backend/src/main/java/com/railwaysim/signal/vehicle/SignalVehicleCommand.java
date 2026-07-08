package com.railwaysim.signal.vehicle;

import com.railwaysim.signal.MovementAuthority;

public record SignalVehicleCommand(
    String trainId,
    double authorityEndMeters,
    double speedLimitMetersPerSecond,
    boolean tractionCutoff,
    boolean serviceBrakeCommand,
    boolean emergencyBrakeCommand,
    String reason
) {
    public SignalVehicleCommand {
        authorityEndMeters = Math.max(0, authorityEndMeters);
        speedLimitMetersPerSecond = Math.max(0, speedLimitMetersPerSecond);
        reason = reason == null || reason.isBlank() ? "SIGNAL_AUTHORITY" : reason;
    }

    public static SignalVehicleCommand fromAuthority(MovementAuthority authority) {
        return new SignalVehicleCommand(
            authority.trainId(),
            authority.authorityEndMeters(),
            authority.speedLimitMetersPerSecond(),
            authority.speedLimitMetersPerSecond() <= 0,
            false,
            false,
            authority.reason()
        );
    }
}
