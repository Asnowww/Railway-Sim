package com.railwaysim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.vision")
public class VisionUdpProperties {

    private String targetHost = "18.32.115.28";
    private int targetPort = 8302;
    private int localPort;

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
}
