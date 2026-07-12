package com.railwaysim.signal;

/**
 * 信号轨道故障类型枚举（WP-05）。
 * 每种故障类型关联安全响应策略，由 {@link SignalService} 和联锁层消费。
 */
public enum SignalTrackFaultType {
    TRACK_CIRCUIT_OCCUPIED,
    TRACK_CIRCUIT_UNKNOWN,
    SWITCH_NO_INDICATION,
    SWITCH_OUT_OF_CONTROL,
    SIGNAL_LAMP_FAILURE,
    SIGNAL_COMM_LOSS,
    TRAIN_POSITION_INVALID,
    TOPOLOGY_DATA_INVALID
}
