package com.railwaysim.api.dto;

/**
 * 外部车辆运行时主动向中央注册列车实例的请求。
 */
public record VehicleRuntimeRegistrationRequest(
    String trainId,
    Integer trainNo,
    Integer linkId,
    Double offsetMeters,
    String direction,
    String reason,
    String traceId,
    String trainType,
    String parameterSetId,
    Double lengthMeters,
    Double emptyMassKg,
    Double maxLoadMassKg
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

    public String normalizedReason() {
        return reason == null || reason.isBlank() ? "VEHICLE_RUNTIME_LAUNCH" : reason.trim();
    }

    public String normalizedTraceId() {
        return traceId == null || traceId.isBlank() ? "vehicle-runtime" : traceId.trim();
    }

    public String normalizedTrainType() {
        return trainType == null ? "" : trainType.trim();
    }

    public String normalizedParameterSetId() {
        return parameterSetId == null ? "" : parameterSetId.trim();
    }

    public double normalizedLengthMeters() {
        return lengthMeters == null || !Double.isFinite(lengthMeters) || lengthMeters <= 0
            ? 0
            : lengthMeters;
    }
}
