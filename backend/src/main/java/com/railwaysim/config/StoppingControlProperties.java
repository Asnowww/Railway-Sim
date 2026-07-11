package com.railwaysim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.stopping-control")
public class StoppingControlProperties {
    private double zeroSpeedMetersPerSecond = 0.2;
    private int stableSeconds = 2;
    private double successToleranceMeters = 1.0;
    private double overrunThresholdMeters = 1.0;
    private String parameterVersion = "STOPPING_V1";

    public double getZeroSpeedMetersPerSecond() { return zeroSpeedMetersPerSecond; }
    public void setZeroSpeedMetersPerSecond(double value) { zeroSpeedMetersPerSecond = value; }
    public int getStableSeconds() { return stableSeconds; }
    public void setStableSeconds(int value) { stableSeconds = value; }
    public double getSuccessToleranceMeters() { return successToleranceMeters; }
    public void setSuccessToleranceMeters(double value) { successToleranceMeters = value; }
    public double getOverrunThresholdMeters() { return overrunThresholdMeters; }
    public void setOverrunThresholdMeters(double value) { overrunThresholdMeters = value; }
    public String getParameterVersion() { return parameterVersion; }
    public void setParameterVersion(String value) { parameterVersion = value; }
}
