package com.railwaysim.vehicleruntime.model;

/**
 * 外部车辆运行时接收中央下发的线路默认值和供电仿真转发配置。
 */
public record VehicleRuntimeBootstrapRequest(
    double defaultLineLengthMeters,
    double defaultSpeedLimitMetersPerSecond,
    double safetyGapMeters,
    String powerNetworkBaseUrl,
    boolean forwardPowerLoads
) {
}
