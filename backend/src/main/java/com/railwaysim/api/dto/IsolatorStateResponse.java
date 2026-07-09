package com.railwaysim.api.dto;

import java.time.Instant;

public record IsolatorStateResponse(
    String id,
    String thirdRailSectionId,
    String state,
    String dataQuality,
    Instant updatedAt
) {
}
