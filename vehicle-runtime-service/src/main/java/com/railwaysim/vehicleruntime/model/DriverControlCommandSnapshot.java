package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriverControlCommandSnapshot(
    String commandId,
    String trainId,
    int sequenceNo,
    Instant receivedAt,
    Instant expiresAt,
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrake,
    double direction,
    boolean doorOpenRequest,
    boolean atoRequest,
    String operationMode,
    String traceId
) {
    public boolean expired(Instant now) {
        return expiresAt != null && now != null && now.isAfter(expiresAt);
    }
}
