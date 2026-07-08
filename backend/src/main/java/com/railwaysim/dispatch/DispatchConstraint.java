package com.railwaysim.dispatch;

public record DispatchConstraint(
    String trainId,
    boolean holdTrain,
    double speedFactor,
    Double targetSpeedMetersPerSecond,
    String reason
) {
    public static DispatchConstraint none(String trainId) {
        return new DispatchConstraint(trainId, false, 1.0, null, "NORMAL");
    }

    public double applyToSpeedLimit(double speedLimitMetersPerSecond) {
        if (holdTrain) {
            return 0;
        }
        double limitedByFactor = speedLimitMetersPerSecond * Math.max(0, Math.min(1, speedFactor));
        if (targetSpeedMetersPerSecond == null) {
            return limitedByFactor;
        }
        return Math.min(limitedByFactor, Math.max(0, targetSpeedMetersPerSecond));
    }
}
