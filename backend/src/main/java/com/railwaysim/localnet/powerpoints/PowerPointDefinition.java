package com.railwaysim.localnet.powerpoints;

public record PowerPointDefinition(
    String pointId,
    String name,
    String direction,
    String dataType,
    String address,
    double scale,
    String defaultValue,
    String targetType,
    String targetId,
    String targetField,
    String operationType,
    String desiredState
) {
    public PowerPointDefinition {
        pointId = blankToDefault(pointId, address);
        name = blankToDefault(name, pointId);
        direction = blankToDefault(direction, "READ").toUpperCase();
        dataType = blankToDefault(dataType, "DOUBLE").toUpperCase();
        address = blankToDefault(address, pointId);
        scale = Double.isFinite(scale) && scale != 0 ? scale : 1.0;
        defaultValue = defaultValue == null ? "0" : defaultValue;
        targetType = blankToDefault(targetType, "POWER_SECTION").toUpperCase();
        targetId = blankToDefault(targetId, pointId);
        targetField = blankToDefault(targetField, "value");
        operationType = blankToDefault(operationType, targetField).toUpperCase();
        desiredState = blankToDefault(desiredState, defaultValue).toUpperCase();
    }

    public boolean readPoint() {
        return "READ".equals(direction) || "BIDIRECTIONAL".equals(direction);
    }

    public boolean writePoint() {
        return "WRITE".equals(direction) || "BIDIRECTIONAL".equals(direction);
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null || fallback.isBlank() ? "" : fallback.trim();
        }
        return value.trim();
    }
}
