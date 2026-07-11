package com.railwaysim.vehicleruntime.model;

import com.railwaysim.vehicleruntime.config.VehicleParameters;

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
    String traceId,
    String trainType,
    String parameterSetId,
    Double lengthMeters,
    Double emptyMassKg,
    Double maxLoadMassKg
) {
    public static CentralTrainRegistrationRequest from(
        VehicleRuntimeLaunchRequest request,
        VehicleParameters parameters
    ) {
        return new CentralTrainRegistrationRequest(
            request.normalizedTrainId(),
            request.trainNo(),
            request.normalizedLinkId(),
            request.normalizedOffsetMeters(),
            request.normalizedDirection(),
            request.normalizedReason(),
            request.normalizedTraceId(),
            parameters.trainType(),
            parameters.parameterSetId(),
            parameters.lengthMeters(),
            parameters.emptyMassKg(),
            parameters.maxLoadMassKg()
        );
    }
}
