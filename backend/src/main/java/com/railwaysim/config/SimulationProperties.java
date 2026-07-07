package com.railwaysim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation")
public class SimulationProperties {

    private long tickMillis = 200;
    private long pushIntervalMillis = 1000;
    private double defaultLineLengthMeters = 5000;
    private double defaultSpeedLimitMetersPerSecond = 22.2;
    private double safetyGapMeters = 120;

    public long getTickMillis() {
        return tickMillis;
    }

    public void setTickMillis(long tickMillis) {
        this.tickMillis = tickMillis;
    }

    public long getPushIntervalMillis() {
        return pushIntervalMillis;
    }

    public void setPushIntervalMillis(long pushIntervalMillis) {
        this.pushIntervalMillis = pushIntervalMillis;
    }

    public double getDefaultLineLengthMeters() {
        return defaultLineLengthMeters;
    }

    public void setDefaultLineLengthMeters(double defaultLineLengthMeters) {
        this.defaultLineLengthMeters = defaultLineLengthMeters;
    }

    public double getDefaultSpeedLimitMetersPerSecond() {
        return defaultSpeedLimitMetersPerSecond;
    }

    public void setDefaultSpeedLimitMetersPerSecond(double defaultSpeedLimitMetersPerSecond) {
        this.defaultSpeedLimitMetersPerSecond = defaultSpeedLimitMetersPerSecond;
    }

    public double getSafetyGapMeters() {
        return safetyGapMeters;
    }

    public void setSafetyGapMeters(double safetyGapMeters) {
        this.safetyGapMeters = safetyGapMeters;
    }
}

