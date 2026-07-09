package com.railwaysim.api.dto;

import java.time.Instant;

public record StrayCurrentRiskResponse(
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
