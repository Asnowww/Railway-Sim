package com.railwaysim.api.dto;

import java.time.Instant;
import java.util.List;

public record PowerEnergyResponse(
    double totalLoadWatts,
    double totalRegenPowerWatts,
    double totalAbsorbedRegenPowerWatts,
    double totalUnabsorbedRegenPowerWatts,
    String statisticsWindow,
    String dataQuality,
    Instant updatedAt,
    List<PowerSectionEnergy> sections
) {
    public record PowerSectionEnergy(
        String sectionId,
        String sectionName,
        double loadWatts,
        double regenPowerWatts,
        double absorbedRegenPowerWatts,
        double unabsorbedRegenPowerWatts,
        String dataQuality
    ) {
    }
}
