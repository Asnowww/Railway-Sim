package com.railwaysim.vehicleruntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部车辆运行时的本地参数，bootstrap 可以覆盖演示线路的默认约束。
 */
@ConfigurationProperties(prefix = "vehicle-runtime")
public class VehicleRuntimeProperties {

    private String trainParamsPath = "config/train_params.yaml";
    private int queueCapacity = 1;
    private double defaultLineLengthMeters = 5000;
    private double defaultSpeedLimitMetersPerSecond = 22.2;
    private double safetyGapMeters = 120;
    private long instanceLeaseMillis = 1000;
    private VehiclePhysicsMode physicsMode = VehiclePhysicsMode.JAVA_FALLBACK;
    private String fmuBaseUrl = "http://localhost:9000";
    private long fmuTimeoutMillis = 80;
    private String fmuModelVersion = "TrainTractionBrake/2.0.0";
    private boolean forwardPowerLoads;
    private String powerNetworkBaseUrl = "http://localhost:9200";
    private long powerNetworkTimeoutMillis = 300;
    private String centralBaseUrl = "http://localhost:8080";
    private long centralTimeoutMillis = 300;
    private boolean autonomousTickEnabled;
    private long autonomousTickIntervalMillis = 100;

    public String getTrainParamsPath() {
        return trainParamsPath;
    }

    public void setTrainParamsPath(String trainParamsPath) {
        this.trainParamsPath = trainParamsPath == null || trainParamsPath.isBlank()
            ? "config/train_params.yaml"
            : trainParamsPath;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    public double getDefaultLineLengthMeters() {
        return defaultLineLengthMeters;
    }

    public void setDefaultLineLengthMeters(double defaultLineLengthMeters) {
        this.defaultLineLengthMeters = defaultLineLengthMeters <= 0 ? 5000 : defaultLineLengthMeters;
    }

    public double getDefaultSpeedLimitMetersPerSecond() {
        return defaultSpeedLimitMetersPerSecond;
    }

    public void setDefaultSpeedLimitMetersPerSecond(double defaultSpeedLimitMetersPerSecond) {
        this.defaultSpeedLimitMetersPerSecond = defaultSpeedLimitMetersPerSecond <= 0
            ? 22.2
            : defaultSpeedLimitMetersPerSecond;
    }

    public double getSafetyGapMeters() {
        return safetyGapMeters;
    }

    public void setSafetyGapMeters(double safetyGapMeters) {
        this.safetyGapMeters = Math.max(0, safetyGapMeters);
    }

    public long getInstanceLeaseMillis() {
        return instanceLeaseMillis;
    }

    public void setInstanceLeaseMillis(long instanceLeaseMillis) {
        this.instanceLeaseMillis = Math.max(1, instanceLeaseMillis);
    }

    public VehiclePhysicsMode getPhysicsMode() {
        return physicsMode;
    }

    public void setPhysicsMode(VehiclePhysicsMode physicsMode) {
        this.physicsMode = physicsMode == null ? VehiclePhysicsMode.JAVA_FALLBACK : physicsMode;
    }

    public String getFmuBaseUrl() {
        return fmuBaseUrl;
    }

    public void setFmuBaseUrl(String fmuBaseUrl) {
        this.fmuBaseUrl = fmuBaseUrl == null || fmuBaseUrl.isBlank()
            ? "http://localhost:9000"
            : fmuBaseUrl;
    }

    public long getFmuTimeoutMillis() {
        return fmuTimeoutMillis;
    }

    public void setFmuTimeoutMillis(long fmuTimeoutMillis) {
        this.fmuTimeoutMillis = Math.max(1, fmuTimeoutMillis);
    }

    public String getFmuModelVersion() {
        return fmuModelVersion;
    }

    public void setFmuModelVersion(String fmuModelVersion) {
        this.fmuModelVersion = fmuModelVersion == null || fmuModelVersion.isBlank()
            ? "TrainTractionBrake/2.0.0"
            : fmuModelVersion;
    }

    public boolean isForwardPowerLoads() {
        return forwardPowerLoads;
    }

    public void setForwardPowerLoads(boolean forwardPowerLoads) {
        this.forwardPowerLoads = forwardPowerLoads;
    }

    public String getPowerNetworkBaseUrl() {
        return powerNetworkBaseUrl;
    }

    public void setPowerNetworkBaseUrl(String powerNetworkBaseUrl) {
        this.powerNetworkBaseUrl = powerNetworkBaseUrl == null || powerNetworkBaseUrl.isBlank()
            ? "http://localhost:9200"
            : powerNetworkBaseUrl;
    }

    public long getPowerNetworkTimeoutMillis() {
        return powerNetworkTimeoutMillis;
    }

    public void setPowerNetworkTimeoutMillis(long powerNetworkTimeoutMillis) {
        this.powerNetworkTimeoutMillis = Math.max(1, powerNetworkTimeoutMillis);
    }

    public String getCentralBaseUrl() {
        return centralBaseUrl;
    }

    public void setCentralBaseUrl(String centralBaseUrl) {
        this.centralBaseUrl = centralBaseUrl == null || centralBaseUrl.isBlank()
            ? "http://localhost:8080"
            : centralBaseUrl;
    }

    public long getCentralTimeoutMillis() {
        return centralTimeoutMillis;
    }

    public void setCentralTimeoutMillis(long centralTimeoutMillis) {
        this.centralTimeoutMillis = Math.max(1, centralTimeoutMillis);
    }

    public boolean isAutonomousTickEnabled() {
        return autonomousTickEnabled;
    }

    public void setAutonomousTickEnabled(boolean autonomousTickEnabled) {
        this.autonomousTickEnabled = autonomousTickEnabled;
    }

    public long getAutonomousTickIntervalMillis() {
        return autonomousTickIntervalMillis;
    }

    public void setAutonomousTickIntervalMillis(long autonomousTickIntervalMillis) {
        this.autonomousTickIntervalMillis = Math.max(10, autonomousTickIntervalMillis);
    }
}
