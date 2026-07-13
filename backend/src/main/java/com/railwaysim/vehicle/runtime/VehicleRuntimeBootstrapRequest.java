package com.railwaysim.vehicle.runtime;

import java.util.List;

/**
 * 中央向已启动的外部车辆运行时同步线路默认值和供电仿真联动配置。
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

    public record StationTarget(
        String id, String name, double positionMeters, List<String> platformIds
    ) {
        public StationTarget {
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
        }
    }
}
