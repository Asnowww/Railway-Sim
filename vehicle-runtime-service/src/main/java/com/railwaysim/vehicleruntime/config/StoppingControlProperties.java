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
    public String getParameterVersion() { return parameterVersion; }
    public void setParameterVersion(String value) {
        parameterVersion = value == null || value.isBlank() ? "STOPPING_V1" : value;
    }

    private double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }
}
