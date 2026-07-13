package com.railwaysim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.vision")
public class VisionUdpProperties {

    private boolean enabled;
    private String trainId = "TR-001";
    private String targetHost = "18.32.115.28";
    private int targetPort = 8302;
    private int localPort;
    private long intervalMillis = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTrainId() {
        return trainId;
    }

    public void setTrainId(String trainId) {
        this.trainId = trainId == null || trainId.isBlank() ? "TR-001" : trainId.trim();
    }

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost == null || targetHost.isBlank() ? "18.32.115.28" : targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort <= 0 ? 8302 : targetPort;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = Math.max(0, localPort);
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(long intervalMillis) {
        this.intervalMillis = Math.max(20, intervalMillis);
    }
}
