package com.railwaysim.dispatch;

import java.time.Instant;
import java.util.Map;

public record DispatchCommand(
    String id,
    String trainId,
    String commandType,
    Map<String, Object> payload,
    String reason,
    String status,
    Instant createdAt,
    Instant appliedAt
) {
}
