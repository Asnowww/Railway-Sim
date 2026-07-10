package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 车辆运行时推给供电仿真的单个供电分区负荷快照。
 */
public record PowerNetworkSectionLoadRequest(
    @JsonProperty("powerSectionId") String powerSectionId,
    @JsonProperty("trainIds") List<String> trainIds,
    @JsonProperty("tractionPowerWatts") double tractionPowerWatts,
    @JsonProperty("regenPowerWatts") double regenPowerWatts,
    @JsonProperty("currentAmps") double currentAmps
) {
}
