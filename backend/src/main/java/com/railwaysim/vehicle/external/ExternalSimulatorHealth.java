package com.railwaysim.vehicle.external;

import java.time.Instant;

public record ExternalSimulatorHealth(
    ExternalSimulatorMode mode,
    String heartbeatStatus,
    Instant lastPacketAt,
    long latencyMillis,
    String dataQuality
) {
    public ExternalSimulatorHealth {
        mode = mode == null ? ExternalSimulatorMode.LOCAL : mode;
        heartbeatStatus = heartbeatStatus == null || heartbeatStatus.isBlank() ? "UNKNOWN" : heartbeatStatus;
        dataQuality = dataQuality == null || dataQuality.isBlank() ? "UNKNOWN" : dataQuality;
        latencyMillis = Math.max(0, latencyMillis);
    }

    public static ExternalSimulatorHealth local() {
        return new ExternalSimulatorHealth(ExternalSimulatorMode.LOCAL, "LOCAL", Instant.now(), 0, "GOOD");
    }

    public static ExternalSimulatorHealth fallback(ExternalSimulatorMode mode, String heartbeatStatus) {
        return new ExternalSimulatorHealth(mode, heartbeatStatus, Instant.now(), 0, "FALLBACK");
    }
}
