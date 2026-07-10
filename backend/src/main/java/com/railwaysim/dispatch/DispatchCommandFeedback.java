package com.railwaysim.dispatch;

import java.time.Instant;
import java.util.Map;

public record DispatchCommandFeedback(
    String commandId,
    String trainId,
    String commandType,
    String feedbackSource,
    String feedbackStatus,
    String reason,
    Instant feedbackAt,
    Map<String, Object> details
) {
    public DispatchCommandFeedback {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
