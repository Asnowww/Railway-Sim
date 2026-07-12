package com.railwaysim.vehicleruntime.model;

import java.time.Instant;

public record DriverCommandAcceptance(
    boolean accepted,
    String commandId,
    String trainId,
    String operationMode,
    String decisionSource,
    String reasonCode,
    Instant receivedAt,
    Instant expiresAt
) {
    public static DriverCommandAcceptance accepted(DriverControlCommandSnapshot cmd) {
        return new DriverCommandAcceptance(true, cmd.commandId(), cmd.trainId(), cmd.operationMode(),
            "DRIVER", "ACCEPTED", cmd.receivedAt(), cmd.expiresAt());
    }

    public static DriverCommandAcceptance rejected(String trainId, String reasonCode, String reason) {
        return new DriverCommandAcceptance(false, null, trainId, null, "REJECTED", reasonCode, Instant.now(), null);
    }
}
