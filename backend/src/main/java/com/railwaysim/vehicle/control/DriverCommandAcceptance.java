package com.railwaysim.vehicle.control;

import java.time.Instant;

/**
 * POST plc-input 的响应结构。
 */
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
    public static DriverCommandAcceptance accepted(DriverControlCommand cmd) {
        return new DriverCommandAcceptance(
            true, cmd.commandId(), cmd.trainId(), cmd.operationMode(),
            "DRIVER", "ACCEPTED",
            cmd.receivedAt(), cmd.expiresAt()
        );
    }

    public static DriverCommandAcceptance rejected(String trainId, String reasonCode, String reason) {
        return new DriverCommandAcceptance(
            false, null, trainId, null, "REJECTED",
            reasonCode, Instant.now(), null
        );
    }
}
