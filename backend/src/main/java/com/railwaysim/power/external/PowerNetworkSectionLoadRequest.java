package com.railwaysim.power.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 中央向供电仿真提交的单个供电分区负荷快照。
 */
public record PowerNetworkSectionLoadRequest(
    @JsonProperty("powerSectionId") String powerSectionId,
    @JsonProperty("trainIds") List<String> trainIds,
    @JsonProperty("tractionPowerWatts") double tractionPowerWatts,
    @JsonProperty("regenPowerWatts") double regenPowerWatts,
    @JsonProperty("currentAmps") double currentAmps
) {
    public PowerNetworkSectionLoadRequest {
        trainIds = trainIds == null ? List.of() : List.copyOf(trainIds);
    }
}
