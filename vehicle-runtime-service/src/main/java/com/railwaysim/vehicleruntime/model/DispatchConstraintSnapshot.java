package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 中央调度模块下发给车辆运行时的约束快照，只表达车辆侧需要消费的限速和站停语义。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchConstraintSnapshot(
    String trainId,
    boolean holdTrain,
    double speedFactor,
    Double targetSpeedMetersPerSecond,
    boolean releaseStationStop,
    String reason
) {
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
