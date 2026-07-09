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
    String supplyMode,
    String isolatorStatus,
    String substationAvailability,
    String breakerStatus,
    String protectionState,
    String maintenanceState,
    String lockoutState,
    String externalDataQuality,
    String strayCurrentRiskLevel,
    String strayCurrentRiskReason,
    List<String> affectedTrainIds,
    String dataQuality,
    Instant updatedAt
) {
    public PowerSectionState {
        affectedTrainIds = affectedTrainIds == null ? List.of() : List.copyOf(affectedTrainIds);
        supplyMode = supplyMode == null || supplyMode.isBlank() ? "DOUBLE_END" : supplyMode;
        isolatorStatus = isolatorStatus == null || isolatorStatus.isBlank() ? "CLOSED" : isolatorStatus;
        substationAvailability = substationAvailability == null || substationAvailability.isBlank()
            ? "AVAILABLE"
            : substationAvailability;
        externalDataQuality = externalDataQuality == null || externalDataQuality.isBlank()
            ? "UNKNOWN"
            : externalDataQuality;
        strayCurrentRiskLevel = strayCurrentRiskLevel == null || strayCurrentRiskLevel.isBlank()
            ? "NORMAL"
            : strayCurrentRiskLevel;
        strayCurrentRiskReason = strayCurrentRiskReason == null ? "" : strayCurrentRiskReason;
        dataQuality = dataQuality == null || dataQuality.isBlank() ? "GOOD" : dataQuality;
    }
}
