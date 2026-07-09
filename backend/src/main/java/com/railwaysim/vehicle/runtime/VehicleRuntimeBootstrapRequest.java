package com.railwaysim.vehicle.runtime;

/**
 * 中央向已启动的外部车辆运行时同步线路默认值和供电仿真联动配置。
 */
public record VehicleRuntimeBootstrapRequest(
    double defaultLineLengthMeters,
    double defaultSpeedLimitMetersPerSecond,
    double safetyGapMeters,
    String powerNetworkBaseUrl,
    boolean forwardPowerLoads
) {
}
