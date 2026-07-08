package com.railwaysim.power;

import java.time.Instant;

public record IsolatorStateView(
    String id,
    String thirdRailSectionId,
    String state,
    String dataQuality,
    Instant updatedAt
) {
}
