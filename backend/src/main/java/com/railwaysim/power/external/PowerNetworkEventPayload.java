package com.railwaysim.power.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PowerNetworkEventPayload(
    String eventType,
    String targetType,
    String targetId,
    String level,
    String detail,
    Instant occurredAt
) {
    public PowerNetworkEventPayload {
        level = level == null || level.isBlank() ? "INFO" : level;
        detail = detail == null ? "" : detail;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
