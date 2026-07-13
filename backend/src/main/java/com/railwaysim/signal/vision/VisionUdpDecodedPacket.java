package com.railwaysim.signal.vision;

import java.util.List;

public record VisionUdpDecodedPacket(
    long counter,
    List<Integer> signalStates,
    List<Integer> switchStates,
    long ownSpeedMillimetersPerSecond,
    int departureCountdownSeconds,
    int operationCode,
    int accelerationPercent,
    long headOffsetMillimeters,
    int segmentNumber,
    int directionCode,
    List<VisionOtherTrainPacket> otherTrains
) {
    public VisionUdpDecodedPacket {
        signalStates = List.copyOf(signalStates);
        switchStates = List.copyOf(switchStates);
        otherTrains = List.copyOf(otherTrains);
    }
}
