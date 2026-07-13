package com.railwaysim.signal.vision;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VisionUdpPacketDecoder {

    public VisionUdpDecodedPacket decode(byte[] payload) {
        if (payload == null || payload.length < 128) {
            throw new IllegalArgumentException("vision UDP Version 1.3 packet is truncated");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long counter = Integer.toUnsignedLong(buffer.getInt());
        int signalCount = Byte.toUnsignedInt(buffer.get());
        if (signalCount != VisionProtocolV13Layout.SIGNAL_IDS.size()) {
            throw new IllegalArgumentException("vision UDP signal count must be 77");
        }
        List<Integer> signals = readUnsignedBytes(buffer, signalCount);
        int switchCount = Byte.toUnsignedInt(buffer.get());
        if (switchCount != VisionProtocolV13Layout.SWITCH_IDS.size()) {
            throw new IllegalArgumentException("vision UDP switch count must be 29");
        }
        List<Integer> switches = readUnsignedBytes(buffer, switchCount);
        long speed = Integer.toUnsignedLong(buffer.getInt());
        int departure = Short.toUnsignedInt(buffer.getShort());
        int operation = Byte.toUnsignedInt(buffer.get());
        int acceleration = buffer.get();
        long headOffset = Integer.toUnsignedLong(buffer.getInt());
        int segment = Short.toUnsignedInt(buffer.getShort());
        int direction = buffer.get();
        int otherCount = Byte.toUnsignedInt(buffer.get());
        int expectedRemaining = otherCount * 9;
        if (buffer.remaining() != expectedRemaining) {
            throw new IllegalArgumentException("vision UDP other-train payload length is invalid");
        }
        long[] positions = new long[otherCount];
        int[] segments = new int[otherCount];
        int[] directions = new int[otherCount];
        int[] speeds = new int[otherCount];
        for (int i = 0; i < otherCount; i++) positions[i] = Integer.toUnsignedLong(buffer.getInt());
        for (int i = 0; i < otherCount; i++) segments[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < otherCount; i++) directions[i] = buffer.get();
        for (int i = 0; i < otherCount; i++) speeds[i] = Short.toUnsignedInt(buffer.getShort());
        List<VisionOtherTrainPacket> others = new ArrayList<>(otherCount);
        for (int i = 0; i < otherCount; i++) {
            others.add(new VisionOtherTrainPacket(positions[i], segments[i], directions[i], speeds[i]));
        }
        return new VisionUdpDecodedPacket(
            counter, signals, switches, speed, departure, operation, acceleration,
            headOffset, segment, direction, others
        );
    }

    private List<Integer> readUnsignedBytes(ByteBuffer buffer, int count) {
        List<Integer> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(Byte.toUnsignedInt(buffer.get()));
        return result;
    }
}
