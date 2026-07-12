package com.railwaysim.vehicleruntime.model;

import java.util.List;

/**
 * 外部车辆运行时接收中央下发的线路默认值和供电仿真转发配置。
 */
public record VehicleRuntimeBootstrapRequest(
    double defaultLineLengthMeters,
    double defaultSpeedLimitMetersPerSecond,
    double safetyGapMeters,
    String powerNetworkBaseUrl,
    boolean forwardPowerLoads,
    List<StationTarget> stations
) {
    public VehicleRuntimeBootstrapRequest {
        stations = stations == null ? List.of() : List.copyOf(stations);
    }

    public VehicleRuntimeBootstrapRequest(
        double defaultLineLengthMeters, double defaultSpeedLimitMetersPerSecond,
        double safetyGapMeters, String powerNetworkBaseUrl, boolean forwardPowerLoads
    ) {
        this(defaultLineLengthMeters, defaultSpeedLimitMetersPerSecond, safetyGapMeters,
            powerNetworkBaseUrl, forwardPowerLoads, List.of());
    }

    public record StationTarget(
        String id, String name, double positionMeters, List<String> platformIds
    ) {
        public StationTarget {
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
        }
    }
}
