package com.railwaysim.signal.vision;

import java.time.Instant;

public record VisionUdpSendResponse(
    String trainId,
    String host,
    int port,
    int localPort,
    int counter,
    int payloadBytes,
    int signalCount,
    int switchCount,
    int otherTrainCount,
    Instant sentAt
) {
}
