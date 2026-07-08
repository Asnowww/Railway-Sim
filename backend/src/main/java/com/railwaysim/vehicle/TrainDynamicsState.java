package com.railwaysim.vehicle;

public enum TrainDynamicsState {
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
