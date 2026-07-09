package com.railwaysim.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.localnet")
public class LocalNetProperties {

    private boolean enabled;
    private boolean auditEnabled = true;
    private int recentPacketLimit = 20;
    private Signal signal = new Signal();
    private DriverCab driverCab = new DriverCab();
    private PowerPoints powerPoints = new PowerPoints();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

    public int getRecentPacketLimit() {
        return recentPacketLimit;
    }

    public void setRecentPacketLimit(int recentPacketLimit) {
        this.recentPacketLimit = Math.max(1, recentPacketLimit);
    }

    public Signal getSignal() {
        return signal;
    }

    public void setSignal(Signal signal) {
        this.signal = signal == null ? new Signal() : signal;
    }

    public DriverCab getDriverCab() {
        return driverCab;
    }

    public void setDriverCab(DriverCab driverCab) {
        this.driverCab = driverCab == null ? new DriverCab() : driverCab;
    }

    public PowerPoints getPowerPoints() {
        return powerPoints;
    }

    public void setPowerPoints(PowerPoints powerPoints) {
        this.powerPoints = powerPoints == null ? new PowerPoints() : powerPoints;
    }

    public static class Signal {

        private boolean enabled;
        private String bindHost = "0.0.0.0";
        private int bindPort = 0;
        private String remoteHost = "127.0.0.1";
        private int remotePort = 0;
        private long sendIntervalMillis = 100;
        private int maxTrains = 20;
        private boolean shadowComparison = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBindHost() {
            return bindHost;
        }

        public void setBindHost(String bindHost) {
            this.bindHost = blankToDefault(bindHost, "0.0.0.0");
        }

        public int getBindPort() {
            return bindPort;
        }

        public void setBindPort(int bindPort) {
            this.bindPort = Math.max(0, bindPort);
        }

        public String getRemoteHost() {
            return remoteHost;
        }

        public void setRemoteHost(String remoteHost) {
            this.remoteHost = blankToDefault(remoteHost, "127.0.0.1");
        }

        public int getRemotePort() {
            return remotePort;
        }

        public void setRemotePort(int remotePort) {
            this.remotePort = Math.max(0, remotePort);
        }

        public long getSendIntervalMillis() {
            return sendIntervalMillis;
        }

        public void setSendIntervalMillis(long sendIntervalMillis) {
            this.sendIntervalMillis = Math.max(20, sendIntervalMillis);
        }

        public int getMaxTrains() {
            return maxTrains;
        }

        public void setMaxTrains(int maxTrains) {
            this.maxTrains = Math.max(1, Math.min(40, maxTrains));
        }

        public boolean isShadowComparison() {
            return shadowComparison;
        }

        public void setShadowComparison(boolean shadowComparison) {
            this.shadowComparison = shadowComparison;
        }
    }

    public static class DriverCab {

        private boolean enabled;
        private long reconnectMillis = 1000;
        private List<DriverCabConnection> connections = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getReconnectMillis() {
            return reconnectMillis;
        }

        public void setReconnectMillis(long reconnectMillis) {
            this.reconnectMillis = Math.max(100, reconnectMillis);
        }

        public List<DriverCabConnection> getConnections() {
            return connections;
        }

        public void setConnections(List<DriverCabConnection> connections) {
            this.connections = connections == null ? new ArrayList<>() : new ArrayList<>(connections);
        }
    }

    public static class DriverCabConnection {

        private String trainId = "TR-001";
        private String role = "PLC";
        private String host = "127.0.0.1";
        private int port;
        private long cycleMillis = 100;
        private long timeoutMillis = 300;

        public String getTrainId() {
            return trainId;
        }

        public void setTrainId(String trainId) {
            this.trainId = blankToDefault(trainId, "TR-001");
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = blankToDefault(role, "PLC");
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = blankToDefault(host, "127.0.0.1");
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = Math.max(0, port);
        }

        public long getCycleMillis() {
            return cycleMillis;
        }

        public void setCycleMillis(long cycleMillis) {
            this.cycleMillis = Math.max(20, cycleMillis);
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = Math.max(1, timeoutMillis);
        }
    }

    public static class PowerPoints {

        private boolean enabled;
        private String pointTablePath = "../config/power-point-table-example.yaml";
        private String driver = "LOCAL_SIM";
        private String httpBaseUrl = "http://localhost:9200";
        private long pollIntervalMillis = 500;
        private long timeoutMillis = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPointTablePath() {
            return pointTablePath;
        }

        public void setPointTablePath(String pointTablePath) {
            this.pointTablePath = blankToDefault(pointTablePath, "../config/power-point-table-example.yaml");
        }

        public String getDriver() {
            return driver;
        }

        public void setDriver(String driver) {
            this.driver = blankToDefault(driver, "LOCAL_SIM");
        }

        public String getHttpBaseUrl() {
            return httpBaseUrl;
        }

        public void setHttpBaseUrl(String httpBaseUrl) {
            this.httpBaseUrl = blankToDefault(httpBaseUrl, "http://localhost:9200");
        }

        public long getPollIntervalMillis() {
            return pollIntervalMillis;
        }

        public void setPollIntervalMillis(long pollIntervalMillis) {
            this.pollIntervalMillis = Math.max(100, pollIntervalMillis);
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = Math.max(1, timeoutMillis);
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
