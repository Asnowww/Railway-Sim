package com.railwaysim.localnet.powerpoints;

import java.time.Instant;

public record PowerPointValue(
    String pointId,
    String value,
    String quality,
    Instant sourceTimestamp
) {
    public PowerPointValue {
        value = value == null ? "" : value;
        quality = quality == null || quality.isBlank() ? "GOOD" : quality;
        sourceTimestamp = sourceTimestamp == null ? Instant.now() : sourceTimestamp;
    }

    public double asDouble(double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public boolean asBoolean() {
        return "true".equalsIgnoreCase(value)
            || "1".equals(value)
            || "CLOSED".equalsIgnoreCase(value)
            || "AVAILABLE".equalsIgnoreCase(value)
            || "ENERGIZED".equalsIgnoreCase(value);
    }
}
