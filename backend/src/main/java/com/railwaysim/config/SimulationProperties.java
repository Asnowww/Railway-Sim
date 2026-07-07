package com.railwaysim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation")
public class SimulationProperties {

    private long tickMillis = 200;
    private long pushIntervalMillis = 1000;
    private long fmuStepMillis = 100;
    private long trackStepMillis = 100;
    private long signalStepMillis = 100;
    private long powerStepMillis = 100;
    private long dispatchStepMillis = 1000;
    private long persistenceStepMillis = 5000;
    private double defaultLineLengthMeters = 5000;
    private double defaultSpeedLimitMetersPerSecond = 22.2;
    private double defaultCurveRadiusMeters = 1000;
    private double safetyGapMeters = 120;
    private String lineDataSource = "database";
    private String lineId = "1";
    private String lineDataPath = "../config/line-demo.yaml";
    private boolean lineDataAutoImport = true;
    private String powerConfigPath = "../config/power_third_rail.yaml";
    private boolean fmuServiceEnabled;
    private String fmuServiceUrl = "http://localhost:9000";
    private long fmuServiceTimeoutMillis = 500;

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

    public long getFmuStepMillis() {
        return fmuStepMillis;
    }

    public void setFmuStepMillis(long fmuStepMillis) {
        this.fmuStepMillis = fmuStepMillis;
    }

    public long getTrackStepMillis() {
        return trackStepMillis;
    }

    public void setTrackStepMillis(long trackStepMillis) {
        this.trackStepMillis = trackStepMillis;
    }

    public long getSignalStepMillis() {
        return signalStepMillis;
    }

    public void setSignalStepMillis(long signalStepMillis) {
        this.signalStepMillis = signalStepMillis;
    }

    public long getPowerStepMillis() {
        return powerStepMillis;
    }

    public void setPowerStepMillis(long powerStepMillis) {
        this.powerStepMillis = powerStepMillis;
    }

    public long getDispatchStepMillis() {
        return dispatchStepMillis;
    }

    public void setDispatchStepMillis(long dispatchStepMillis) {
        this.dispatchStepMillis = dispatchStepMillis;
    }

    public long getPersistenceStepMillis() {
        return persistenceStepMillis;
    }

    public void setPersistenceStepMillis(long persistenceStepMillis) {
        this.persistenceStepMillis = persistenceStepMillis;
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

    public double getDefaultCurveRadiusMeters() {
        return defaultCurveRadiusMeters;
    }

    public void setDefaultCurveRadiusMeters(double defaultCurveRadiusMeters) {
        this.defaultCurveRadiusMeters = defaultCurveRadiusMeters;
    }

    public double getSafetyGapMeters() {
        return safetyGapMeters;
    }

    public void setSafetyGapMeters(double safetyGapMeters) {
        this.safetyGapMeters = safetyGapMeters;
    }

    public String getLineDataPath() {
        return lineDataPath;
    }

    public void setLineDataPath(String lineDataPath) {
        this.lineDataPath = lineDataPath;
    }

    public String getLineDataSource() {
        return lineDataSource;
    }

    public void setLineDataSource(String lineDataSource) {
        this.lineDataSource = lineDataSource;
    }

    public String getLineId() {
        return lineId;
    }

    public void setLineId(String lineId) {
        this.lineId = lineId;
    }

    public boolean isLineDataAutoImport() {
        return lineDataAutoImport;
    }

    public void setLineDataAutoImport(boolean lineDataAutoImport) {
        this.lineDataAutoImport = lineDataAutoImport;
    }

    public String getPowerConfigPath() {
        return powerConfigPath;
    }

    public void setPowerConfigPath(String powerConfigPath) {
        this.powerConfigPath = powerConfigPath;
    }

    public boolean isFmuServiceEnabled() {
        return fmuServiceEnabled;
    }

    public void setFmuServiceEnabled(boolean fmuServiceEnabled) {
        this.fmuServiceEnabled = fmuServiceEnabled;
    }

    public String getFmuServiceUrl() {
        return fmuServiceUrl;
    }

    public void setFmuServiceUrl(String fmuServiceUrl) {
        this.fmuServiceUrl = fmuServiceUrl;
    }

    public long getFmuServiceTimeoutMillis() {
        return fmuServiceTimeoutMillis;
    }

    public void setFmuServiceTimeoutMillis(long fmuServiceTimeoutMillis) {
        this.fmuServiceTimeoutMillis = fmuServiceTimeoutMillis;
    }
}
