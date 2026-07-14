package com.railwaysim.signal.vision;

import java.time.Instant;

public record VisionUdpPublisherStatus(
    boolean enabled,
    String trainId,
    String targetHost,
    int targetPort,
    int localPort,
    long intervalMillis,
    long sentPackets,
    long failedPackets,
    Instant lastSentAt,
    String lastError
) {
}
