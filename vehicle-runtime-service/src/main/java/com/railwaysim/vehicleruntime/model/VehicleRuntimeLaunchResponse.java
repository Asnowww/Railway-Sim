package com.railwaysim.vehicleruntime.model;

/**
 * 启动列车实例后返回本地实例状态和中央注册结果。
 */
public record VehicleRuntimeLaunchResponse(
    String trainId,
    VehicleRuntimeInstanceState instanceState,
    String centralRegistrationStatus,
    String reason
) {
}
