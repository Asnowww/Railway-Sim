package com.railwaysim.config;

import com.railwaysim.vehicle.runtime.VehicleRuntimeMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 中央到外部车辆运行时的连接配置，默认保持本地车辆链路。
 */
@ConfigurationProperties(prefix = "railway.simulation.vehicle-runtime")
public class VehicleRuntimeProperties {

    private VehicleRuntimeMode mode = VehicleRuntimeMode.LOCAL;
    private String baseUrl = "http://localhost:9300";
    private long timeoutMillis = 150;
    private boolean autoBootstrap = true;

    public VehicleRuntimeMode getMode() {
        return mode;
    }

    public void setMode(VehicleRuntimeMode mode) {
        this.mode = mode == null ? VehicleRuntimeMode.LOCAL : mode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:9300" : baseUrl;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = Math.max(1, timeoutMillis);
    }

    public boolean isAutoBootstrap() {
        return autoBootstrap;
    }

    public void setAutoBootstrap(boolean autoBootstrap) {
        this.autoBootstrap = autoBootstrap;
    }
}
