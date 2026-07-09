package com.railwaysim.power.external;

import java.util.List;

/**
 * 中央向供电仿真提交的单个供电分区负荷快照。
 */
public record PowerNetworkSectionLoadRequest(
    String powerSectionId,
    List<String> trainIds,
    double tractionPowerWatts,
    double regenPowerWatts,
    double currentAmps
) {
    public PowerNetworkSectionLoadRequest {
        trainIds = trainIds == null ? List.of() : List.copyOf(trainIds);
    }
}
