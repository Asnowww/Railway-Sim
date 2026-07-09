package com.railwaysim.localnet.powerpoints;

public record PowerPointDefinition(
    String pointId,
    String name,
    String direction,
    String dataType,
    String address,
    double scale,
    String defaultValue,
    String domainTarget,
    String quality,
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
        String[] parsedTarget = parseDomainTarget(domainTarget);
        targetType = blankToDefault(targetType, blankToDefault(parsedTarget[0], "POWER_SECTION")).toUpperCase();
        targetId = blankToDefault(targetId, blankToDefault(parsedTarget[1], pointId));
        targetField = blankToDefault(targetField, blankToDefault(parsedTarget[2], "value"));
        domainTarget = blankToDefault(domainTarget, targetType + ":" + targetId + ":" + targetField);
        quality = blankToDefault(quality, "GOOD").toUpperCase();
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

    private static String[] parseDomainTarget(String domainTarget) {
        if (domainTarget == null || domainTarget.isBlank()) {
            return new String[] {"", "", ""};
        }
        String[] parts = domainTarget.trim().split(":", 3);
        return new String[] {
            parts.length > 0 ? parts[0] : "",
            parts.length > 1 ? parts[1] : "",
            parts.length > 2 ? parts[2] : ""
        };
    }
}
