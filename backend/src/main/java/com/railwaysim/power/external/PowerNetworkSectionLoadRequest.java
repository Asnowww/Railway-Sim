package com.railwaysim.power.external;

import java.util.List;

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
