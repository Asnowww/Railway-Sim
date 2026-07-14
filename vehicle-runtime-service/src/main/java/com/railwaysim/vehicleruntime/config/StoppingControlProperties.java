package com.railwaysim.vehicleruntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mirrors the versioned stopping-control contract used by the central fallback controller. */
@ConfigurationProperties(prefix = "vehicle-runtime.stopping-control")
public class StoppingControlProperties {
    private double serviceBrakeDecelerationMetersPerSecondSquared = 0.9;
    private double stationStopWindowMeters = 10.0;
    private double minimumApproachBufferMeters = 30.0;
    private double maximumApproachBufferMeters = 140.0;
    private double approachBufferSeconds = 6.0;
    private double minimumEffectiveDecelerationMetersPerSecondSquared = 0.35;
    private double maximumEffectiveDecelerationMetersPerSecondSquared = 1.25;
    private double zeroSpeedMetersPerSecond = 0.2;
    /** 停车点对位容差：车头与站台停车点偏差在此范围内视为已停准。 */
    private double alignmentToleranceMeters = 0.02;
    /** 贴靠窗口：物理积分落点进入停车点前该窗口且低速时，位置精确贴到停车点。 */
    private double snapWindowMeters = 0.30;
    /** 蠕行对位速度上限：低于此速度仍未到停车点时以蠕行推进。 */
    private double creepSpeedMetersPerSecond = 0.5;
    /** 蠕行牵引指令（0~1）：足以克服起动阻力的小牵引。 */
    private double creepTractionCommand = 0.15;
    private String parameterVersion = "STOPPING_V1";

    public double getServiceBrakeDecelerationMetersPerSecondSquared() {
        return serviceBrakeDecelerationMetersPerSecondSquared;
    }
    public void setServiceBrakeDecelerationMetersPerSecondSquared(double value) {
        serviceBrakeDecelerationMetersPerSecondSquared = positive(value, 0.9);
    }
    public double getStationStopWindowMeters() { return stationStopWindowMeters; }
    public void setStationStopWindowMeters(double value) { stationStopWindowMeters = positive(value, 10.0); }
    public double getMinimumApproachBufferMeters() { return minimumApproachBufferMeters; }
    public void setMinimumApproachBufferMeters(double value) {
        minimumApproachBufferMeters = positive(value, 30.0);
    }
    public double getMaximumApproachBufferMeters() { return maximumApproachBufferMeters; }
    public void setMaximumApproachBufferMeters(double value) {
        maximumApproachBufferMeters = positive(value, 140.0);
    }
    public double getApproachBufferSeconds() { return approachBufferSeconds; }
    public void setApproachBufferSeconds(double value) { approachBufferSeconds = positive(value, 6.0); }
    public double getMinimumEffectiveDecelerationMetersPerSecondSquared() {
        return minimumEffectiveDecelerationMetersPerSecondSquared;
    }
    public void setMinimumEffectiveDecelerationMetersPerSecondSquared(double value) {
        minimumEffectiveDecelerationMetersPerSecondSquared = positive(value, 0.35);
    }
    public double getMaximumEffectiveDecelerationMetersPerSecondSquared() {
        return maximumEffectiveDecelerationMetersPerSecondSquared;
    }
    public void setMaximumEffectiveDecelerationMetersPerSecondSquared(double value) {
        maximumEffectiveDecelerationMetersPerSecondSquared = positive(value, 1.25);
    }
    public double getZeroSpeedMetersPerSecond() { return zeroSpeedMetersPerSecond; }
    public void setZeroSpeedMetersPerSecond(double value) {
        zeroSpeedMetersPerSecond = positive(value, 0.2);
    }
    public double getAlignmentToleranceMeters() { return alignmentToleranceMeters; }
    public void setAlignmentToleranceMeters(double value) { alignmentToleranceMeters = positive(value, 0.02); }
    public double getSnapWindowMeters() { return snapWindowMeters; }
    public void setSnapWindowMeters(double value) { snapWindowMeters = positive(value, 0.30); }
    public double getCreepSpeedMetersPerSecond() { return creepSpeedMetersPerSecond; }
    public void setCreepSpeedMetersPerSecond(double value) { creepSpeedMetersPerSecond = positive(value, 0.5); }
    public double getCreepTractionCommand() { return creepTractionCommand; }
    public void setCreepTractionCommand(double value) { creepTractionCommand = positive(value, 0.15); }
    public String getParameterVersion() { return parameterVersion; }
    public void setParameterVersion(String value) {
        parameterVersion = value == null || value.isBlank() ? "STOPPING_V1" : value;
    }

    private double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }
}
