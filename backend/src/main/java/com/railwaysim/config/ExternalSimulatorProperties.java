package com.railwaysim.config;

import com.railwaysim.vehicle.external.ExternalSimulatorMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.simulation.external-simulator")
public class ExternalSimulatorProperties {

    private ExternalSimulatorMode mode = ExternalSimulatorMode.LOCAL;
    private int maxTrains = 20;
    private Map<String, Integer> segmentMapping = new LinkedHashMap<>();
    private UdpProperties udp = new UdpProperties();
    private RtLabProperties rtLab = new RtLabProperties();
    private ShadowProperties shadow = new ShadowProperties();

    public ExternalSimulatorMode getMode() {
        return mode;
    }

    public void setMode(ExternalSimulatorMode mode) {
        this.mode = mode == null ? ExternalSimulatorMode.LOCAL : mode;
    }

    public int getMaxTrains() {
        return maxTrains;
    }

    public void setMaxTrains(int maxTrains) {
        this.maxTrains = Math.max(1, Math.min(20, maxTrains));
    }

    public Map<String, Integer> getSegmentMapping() {
        return segmentMapping;
    }

    public void setSegmentMapping(Map<String, Integer> segmentMapping) {
        this.segmentMapping = segmentMapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(segmentMapping);
    }

    public UdpProperties getUdp() {
        return udp;
    }

    public void setUdp(UdpProperties udp) {
        this.udp = udp == null ? new UdpProperties() : udp;
    }

    public RtLabProperties getRtLab() {
        return rtLab;
    }

    public void setRtLab(RtLabProperties rtLab) {
        this.rtLab = rtLab == null ? new RtLabProperties() : rtLab;
    }

    public ShadowProperties getShadow() {
        return shadow;
    }

    public void setShadow(ShadowProperties shadow) {
        this.shadow = shadow == null ? new ShadowProperties() : shadow;
    }

    public static class UdpProperties {

        private String modelHost = "192.168.200.110";
        private int modelPort = 23001;
        private String platformHost = "192.168.200.102";
        private int platformPort = 23002;
        private long timeoutMillis = 60;

        public String getModelHost() {
            return modelHost;
        }

        public void setModelHost(String modelHost) {
            this.modelHost = modelHost;
        }

        public int getModelPort() {
            return modelPort;
        }

        public void setModelPort(int modelPort) {
            this.modelPort = modelPort;
        }

        public String getPlatformHost() {
            return platformHost;
        }

        public void setPlatformHost(String platformHost) {
            this.platformHost = platformHost;
        }

        public int getPlatformPort() {
            return platformPort;
        }

        public void setPlatformPort(int platformPort) {
            this.platformPort = platformPort;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = Math.max(1, timeoutMillis);
        }
    }

    public static class RtLabProperties {

        private String projectPath = "";
        private long timeoutMillis = 500;
        private boolean stubEnabled = true;

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String projectPath) {
            this.projectPath = projectPath;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = Math.max(1, timeoutMillis);
        }

        public boolean isStubEnabled() {
            return stubEnabled;
        }

        public void setStubEnabled(boolean stubEnabled) {
            this.stubEnabled = stubEnabled;
        }
    }

    public static class ShadowProperties {

        private double speedToleranceMetersPerSecond = 0.5;
        private double accelerationToleranceMetersPerSecondSquared = 0.3;
        private double distanceToleranceMeters = 2.0;

        public double getSpeedToleranceMetersPerSecond() {
            return speedToleranceMetersPerSecond;
        }

        public void setSpeedToleranceMetersPerSecond(double speedToleranceMetersPerSecond) {
            this.speedToleranceMetersPerSecond = Math.max(0, speedToleranceMetersPerSecond);
        }

        public double getAccelerationToleranceMetersPerSecondSquared() {
            return accelerationToleranceMetersPerSecondSquared;
        }

        public void setAccelerationToleranceMetersPerSecondSquared(double accelerationToleranceMetersPerSecondSquared) {
            this.accelerationToleranceMetersPerSecondSquared = Math.max(0, accelerationToleranceMetersPerSecondSquared);
        }

        public double getDistanceToleranceMeters() {
            return distanceToleranceMeters;
        }

        public void setDistanceToleranceMeters(double distanceToleranceMeters) {
            this.distanceToleranceMeters = Math.max(0, distanceToleranceMeters);
        }
    }
}
