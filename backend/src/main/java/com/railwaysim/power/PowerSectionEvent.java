package com.railwaysim.power;

import java.time.Instant;
import java.util.List;

public record PowerSectionEvent(
    String sectionId,
    String eventType,
    String state,
    String detail,
    int level,
    List<String> affectedTrainIds,
    Instant occurredAt
) {
    public PowerSectionEvent {
        affectedTrainIds = affectedTrainIds == null ? List.of() : List.copyOf(affectedTrainIds);
    }
}
