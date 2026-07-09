package com.railwaysim.vehicleruntime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部车辆运行时的本地参数，bootstrap 可以覆盖演示线路的默认约束。
 */
@ConfigurationProperties(prefix = "vehicle-runtime")
public class VehicleRuntimeProperties {

    private int queueCapacity = 1;
    private double defaultLineLengthMeters = 5000;
    private double defaultSpeedLimitMetersPerSecond = 22.2;
    private double safetyGapMeters = 120;
    private long instanceLeaseMillis = 1000;

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
}
