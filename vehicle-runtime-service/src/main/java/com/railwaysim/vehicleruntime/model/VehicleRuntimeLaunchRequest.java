package com.railwaysim.vehicleruntime.model;

/**
 * 车辆仿真系统主动启动列车实例并注册中央的请求。
 */
public record VehicleRuntimeLaunchRequest(
    String trainId,
    Integer trainNo,
    Integer linkId,
    Double offsetMeters,
    String direction,
    Boolean registerWithCentral,
    String reason,
    String traceId
) {
    public String normalizedTrainId() {
        if (trainId != null && !trainId.isBlank()) {
            return trainId.trim();
        }
        if (trainNo != null) {
            return "TR-%03d".formatted(trainNo);
        }
        return "";
    }

    public int normalizedLinkId() {
        return linkId == null ? 0 : Math.max(0, linkId);
    }

    public double normalizedOffsetMeters() {
        return offsetMeters == null ? 0 : Math.max(0, offsetMeters);
    }

    public String normalizedDirection() {
        return direction == null || direction.isBlank() ? "UNKNOWN" : direction.trim().toUpperCase();
    }

    public boolean shouldRegisterWithCentral() {
        return registerWithCentral == null || registerWithCentral;
    }

    public String normalizedReason() {
        return reason == null || reason.isBlank() ? "VEHICLE_RUNTIME_LAUNCH" : reason.trim();
    }

    public String normalizedTraceId() {
        return traceId == null || traceId.isBlank() ? "vehicle-runtime-launch" : traceId.trim();
    }
}
