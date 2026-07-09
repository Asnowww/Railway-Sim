package com.railwaysim.api.dto;

import com.railwaysim.power.external.ExternalPowerNetworkMode;
import java.time.Instant;

public record ExternalPowerNetworkHealthResponse(
    ExternalPowerNetworkMode mode,
    String heartbeatStatus,
    Instant lastPacketAt,
    long latencyMillis,
    String dataQuality
) {
}
