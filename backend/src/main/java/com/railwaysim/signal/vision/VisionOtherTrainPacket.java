package com.railwaysim.signal.vision;

public record VisionOtherTrainPacket(
    long headOffsetMillimeters,
    int segmentNumber,
    int directionCode,
    int speedCentimetersPerSecond
) {
}
