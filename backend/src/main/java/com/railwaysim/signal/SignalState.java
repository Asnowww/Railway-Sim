package com.railwaysim.signal;

/**
 * 单个信号机的运行时状态，随每一 tick 更新并推送前端。
 */
public record SignalState(
    /** 信号机唯一标识 */
    String signalId,
    /** 信号机所在区段 ID */
    String segmentId,
    /** 信号机在线路上的公里标位置(米) */
    double positionMeters,
    /** 当前灯色 */
    SignalAspect aspect,
    /** 该信号机正在保护的前方列车 ID（若为 RED/YELLOW）；无则为 null */
    String reasonTrainId
) {
}
