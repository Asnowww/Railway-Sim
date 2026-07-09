package com.railwaysim.vehicleruntime.model;

/**
 * 车辆运行时向中央系统注册列车镜像的请求体。
 */
public record CentralTrainRegistrationRequest(
    String trainId,
    Integer trainNo,
    Integer linkId,
    Double offsetMeters,
    String direction,
    String reason,
    String traceId
) {
    public static CentralTrainRegistrationRequest from(VehicleRuntimeLaunchRequest request) {
        return new CentralTrainRegistrationRequest(
            request.normalizedTrainId(),
            request.trainNo(),
            request.normalizedLinkId(),
            request.normalizedOffsetMeters(),
            request.normalizedDirection(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
    }
}
