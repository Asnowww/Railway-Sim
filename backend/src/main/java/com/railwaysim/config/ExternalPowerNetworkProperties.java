package com.railwaysim.config;

import com.railwaysim.power.external.ExternalPowerNetworkMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.external-power-network")
public class ExternalPowerNetworkProperties {

    private ExternalPowerNetworkMode mode = ExternalPowerNetworkMode.LOCAL;
    private String baseUrl = "http://localhost:9200";
    private long timeoutMillis = 300;
    private boolean autoBootstrap = true;

    public ExternalPowerNetworkMode getMode() {
        return mode;
    }

    public void setMode(ExternalPowerNetworkMode mode) {
        this.mode = mode == null ? ExternalPowerNetworkMode.LOCAL : mode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:9200" : baseUrl;
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
