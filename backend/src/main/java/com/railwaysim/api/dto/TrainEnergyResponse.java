package com.railwaysim.api.dto;

import java.time.Instant;

public record TrainEnergyResponse(
    String trainId,
    double energyConsumedKwh,
    double energyRegeneratedKwh,
    double netEnergyKwh,
    String statisticsWindow,
    String dataQuality,
    Instant updatedAt
) {
}
