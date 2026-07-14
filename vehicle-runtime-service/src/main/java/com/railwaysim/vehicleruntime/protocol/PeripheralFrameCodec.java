package com.railwaysim.vehicleruntime.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public class PeripheralFrameCodec {
    public static final int VERSION = 1;
    public static final int FIXED_HEADER_BYTES = 20;
    public static final int CRC_BYTES = 4;
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final byte[] MAGIC = {0x52, 0x53, 0x49, 0x4d};

    public byte[] encode(PeripheralFrame frame) {
        byte[] trainId = frame.trainId().getBytes(StandardCharsets.UTF_8);
        byte[] payload = frame.payload();
        if (trainId.length > 64) throw new IllegalArgumentException("trainId exceeds 64 UTF-8 bytes");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("peripheral payload is too large");
        int withoutCrc = FIXED_HEADER_BYTES + trainId.length + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(withoutCrc + CRC_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC).put((byte) VERSION).put((byte) frame.channel().code());
        buffer.putShort((short) FIXED_HEADER_BYTES).putInt(frame.sequence());
        buffer.putShort((short) trainId.length).putInt(payload.length).putShort((short) frame.flags());
        buffer.put(trainId).put(payload).putInt((int) crc32(buffer.array(), withoutCrc));
        return buffer.array();
    }

    public PeripheralFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length < FIXED_HEADER_BYTES + CRC_BYTES)
            throw new IllegalArgumentException("aggregated peripheral frame is truncated");
        requireMagic(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version = Byte.toUnsignedInt(buffer.get(4));
        if (version != VERSION) throw new IllegalArgumentException("unsupported aggregated peripheral version: " + version);
        if (Short.toUnsignedInt(buffer.getShort(6)) != FIXED_HEADER_BYTES)
            throw new IllegalArgumentException("aggregated peripheral header length is invalid");
        int trainIdLength = Short.toUnsignedInt(buffer.getShort(12));
        int payloadLength = buffer.getInt(14);
        if (trainIdLength > 64 || payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("aggregated peripheral length field is invalid");
        int expectedLength = FIXED_HEADER_BYTES + trainIdLength + payloadLength + CRC_BYTES;
        if (bytes.length != expectedLength) throw new IllegalArgumentException("aggregated peripheral total length is invalid");
        long expectedCrc = Integer.toUnsignedLong(buffer.getInt(bytes.length - CRC_BYTES));
        if (crc32(bytes, bytes.length - CRC_BYTES) != expectedCrc)
            throw new IllegalArgumentException("aggregated peripheral CRC32 is invalid");
        byte[] trainId = new byte[trainIdLength];
        byte[] payload = new byte[payloadLength];
        buffer.position(FIXED_HEADER_BYTES);
        buffer.get(trainId).get(payload);
        return new PeripheralFrame(
            PeripheralChannel.fromCode(Byte.toUnsignedInt(buffer.get(5))), buffer.getInt(8),
            Short.toUnsignedInt(buffer.getShort(18)), new String(trainId, StandardCharsets.UTF_8), payload
        );
    }

    private void requireMagic(byte[] bytes) {
        for (int i = 0; i < MAGIC.length; i++)
            if (bytes[i] != MAGIC[i]) throw new IllegalArgumentException("aggregated peripheral magic is invalid");
    }

    private long crc32(byte[] bytes, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, length);
        return crc.getValue();
    }
}
