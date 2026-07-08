package com.railwaysim.power;

import java.time.Instant;
import java.util.List;

public record PowerSectionState(
    String id,
    String name,
    String substationId,
    String feederId,
    double startMeters,
    double endMeters,
    double voltage,
    double current,
    String status,
    double loadWatts,
    double regenPowerWatts,
    double absorbedRegenPowerWatts,
    double unabsorbedRegenPowerWatts,
    double availablePowerWatts,
    String breakerStatus,
    String protectionState,
    String maintenanceState,
    String lockoutState,
    List<String> affectedTrainIds,
    String dataQuality,
    Instant updatedAt
) {
    public PowerSectionState {
        affectedTrainIds = affectedTrainIds == null ? List.of() : List.copyOf(affectedTrainIds);
        dataQuality = dataQuality == null || dataQuality.isBlank() ? "GOOD" : dataQuality;
    }
}
