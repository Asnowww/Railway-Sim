package com.railwaysim.power;

import java.util.List;

public record PowerSectionLoadSnapshot(
    String powerSectionId,
    List<String> trainIds,
    double tractionPowerWatts,
    double regenPowerWatts,
    double currentAmps
) {
    public PowerSectionLoadSnapshot {
        trainIds = trainIds == null ? List.of() : List.copyOf(trainIds);
    }
}
