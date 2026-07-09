package com.railwaysim.vehicleruntime.model;

import java.util.List;

/**
 * 车辆运行时推给供电仿真的单个供电分区负荷快照。
 */
public record PowerNetworkSectionLoadRequest(
    String powerSectionId,
    List<String> trainIds,
    double tractionPowerWatts,
    double regenPowerWatts,
    double currentAmps
) {
}
