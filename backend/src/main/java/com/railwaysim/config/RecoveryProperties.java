package com.railwaysim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.recovery")
public class RecoveryProperties {
    private long staleAfterMillis = 1000;
    private long maximumTickLag = 1;

    public long getStaleAfterMillis() { return staleAfterMillis; }
    public void setStaleAfterMillis(long value) { staleAfterMillis = Math.max(1, value); }
    public long getMaximumTickLag() { return maximumTickLag; }
    public void setMaximumTickLag(long value) { maximumTickLag = Math.max(0, value); }
}
