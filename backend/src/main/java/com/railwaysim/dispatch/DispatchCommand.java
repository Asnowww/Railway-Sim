package com.railwaysim.dispatch;

import java.time.Instant;

public record DispatchCommand(
    String id,
    String trainId,
    String commandType,
    String detail,
    Instant createdAt
) {
}

