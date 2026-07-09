package com.railwaysim.signal.vision;

public record VisionUdpPacket(
    String trainId,
    int counter,
    int signalCount,
    int switchCount,
    int otherTrainCount,
    byte[] payload
) {
}
