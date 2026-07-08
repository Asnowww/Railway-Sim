package com.railwaysim.power.external;

import java.time.Instant;

public record ExternalPowerNetworkHealth(
    ExternalPowerNetworkMode mode,
    String heartbeatStatus,
    Instant lastPacketAt,
    long latencyMillis,
    String dataQuality
) {
    public ExternalPowerNetworkHealth {
        mode = mode == null ? ExternalPowerNetworkMode.LOCAL : mode;
        heartbeatStatus = heartbeatStatus == null || heartbeatStatus.isBlank() ? "UNKNOWN" : heartbeatStatus;
        lastPacketAt = lastPacketAt == null ? Instant.now() : lastPacketAt;
        latencyMillis = Math.max(0, latencyMillis);
        dataQuality = dataQuality == null || dataQuality.isBlank() ? "UNKNOWN" : dataQuality;
    }

    public static ExternalPowerNetworkHealth local() {
        return new ExternalPowerNetworkHealth(ExternalPowerNetworkMode.LOCAL, "LOCAL", Instant.now(), 0, "GOOD");
    }

    public static ExternalPowerNetworkHealth fallback(ExternalPowerNetworkMode mode, String heartbeatStatus) {
        return new ExternalPowerNetworkHealth(mode, heartbeatStatus, Instant.now(), 0, "FALLBACK");
    }
}
