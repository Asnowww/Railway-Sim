package com.railwaysim.power;

import java.time.Instant;

public record StrayCurrentRiskView(
    String id,
    String sectionId,
    String cabinetState,
    double polarizedPotentialVolts,
    String riskLevel,
    String riskReason,
    String suggestedAction,
    String dataQuality,
    Instant updatedAt
) {
}
