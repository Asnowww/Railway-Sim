package com.railwaysim.vehicleruntime.runtime;

/**
 * 外部控制队列输出的车辆动力学决策状态，保持和中央演示语义一致。
 */
enum TrainDynamicsState {
    SELF_CHECK_BLOCKED,
    SAFETY_BRAKE,
    POWER_LOSS,
    MA_BRAKE,
    STATION_STOPPED,
    STATION_BRAKE,
    OVERSPEED_BRAKE,
    POWER_DERATED,
    OVERLOAD_DERATED,
    ACCELERATING,
    CRUISING,
    COASTING
}
