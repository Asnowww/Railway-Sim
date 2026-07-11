package com.railwaysim.vehicleruntime.model;

public record FmuTrainErrorDto(
    String trainId,
    String faultCode,
    String message,
    String instanceState,
    String dataQuality,
    String fmiStatus
) {
}
