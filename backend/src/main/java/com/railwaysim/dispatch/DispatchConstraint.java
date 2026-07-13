package com.railwaysim.dispatch;

import java.util.List;

public record DispatchConstraint(
    String trainId,
    boolean holdTrain,
    double speedFactor,
    Double targetSpeedMetersPerSecond,
    boolean releaseStationStop,
    String reason,
    List<String> sourceCommandIds,
    Integer deltaDwellSec
) {
    public DispatchConstraint {
        sourceCommandIds = sourceCommandIds == null ? List.of() : List.copyOf(sourceCommandIds);
    }

    public DispatchConstraint(
        String trainId,
        boolean holdTrain,
        double speedFactor,
        Double targetSpeedMetersPerSecond,
        boolean releaseStationStop,
        String reason,
        List<String> sourceCommandIds
    ) {
        this(trainId, holdTrain, speedFactor, targetSpeedMetersPerSecond, releaseStationStop, reason, sourceCommandIds, null);
    }

    public DispatchConstraint(
        String trainId,
        boolean holdTrain,
        double speedFactor,
        Double targetSpeedMetersPerSecond,
        boolean releaseStationStop,
        String reason
    ) {
        this(trainId, holdTrain, speedFactor, targetSpeedMetersPerSecond, releaseStationStop, reason, List.of(), null);
    }

    public static DispatchConstraint none(String trainId) {
        return new DispatchConstraint(trainId, false, 1.0, null, false, "NORMAL");
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
