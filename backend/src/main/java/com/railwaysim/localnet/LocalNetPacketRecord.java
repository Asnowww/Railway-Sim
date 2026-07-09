package com.railwaysim.localnet;

import java.time.Instant;

public record LocalNetPacketRecord(
    ProtocolFamily family,
    String adapterId,
    PacketDirection direction,
    int byteLength,
    String summary,
    String status,
    String error,
    Instant recordedAt
) {
    public LocalNetPacketRecord {
        adapterId = adapterId == null || adapterId.isBlank() ? "unknown" : adapterId;
        summary = summary == null ? "" : summary;
        status = status == null || status.isBlank() ? "UNKNOWN" : status;
        error = error == null ? "" : error;
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
    }
}
