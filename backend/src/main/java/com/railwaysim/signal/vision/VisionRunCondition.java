package com.railwaysim.signal.vision;

public enum VisionRunCondition {
    TRACTION,
    BRAKE,
    COAST;

    static VisionRunCondition from(String value) {
        if (value == null || value.isBlank()) {
            return COAST;
        }
        return switch (value.trim().toUpperCase()) {
            case "TRACTION", "APPLYING", "DERATED", "牵引" -> TRACTION;
            case "BRAKE", "BRAKING", "SERVICE", "REGENERATIVE", "EMERGENCY", "制动" -> BRAKE;
            default -> COAST;
        };
    }
}
