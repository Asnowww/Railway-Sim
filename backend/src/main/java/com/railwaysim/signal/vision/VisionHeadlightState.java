package com.railwaysim.signal.vision;

public enum VisionHeadlightState {
    OFF,
    LOW,
    HIGH;

    static VisionHeadlightState from(String value) {
        if (value == null || value.isBlank()) {
            return HIGH;
        }
        return switch (value.trim().toUpperCase()) {
            case "OFF", "0", "CLOSED" -> OFF;
            case "LOW", "1" -> LOW;
            default -> HIGH;
        };
    }
}
