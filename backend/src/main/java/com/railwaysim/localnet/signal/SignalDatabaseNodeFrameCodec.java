package com.railwaysim.localnet.signal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class SignalDatabaseNodeFrameCodec {

    public static final int HEADER_BYTES = 12;
    public static final byte[] CENTRAL_SOURCE_ID = {0x01, 0x00, 0x01, 0x00};
    public static final byte[] SIGNAL_SOURCE_ID = {0x00, 0x10, 0x00, 0x10};

    private final ByteOrder byteOrder;

    public SignalDatabaseNodeFrameCodec() {
        this(ByteOrder.LITTLE_ENDIAN);
    }

    public SignalDatabaseNodeFrameCodec(ByteOrder byteOrder) {
        this.byteOrder = byteOrder == null ? ByteOrder.LITTLE_ENDIAN : byteOrder;
    }

    public SignalDatabaseNodeFrame decode(byte[] payload) {
        if (payload == null || payload.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Signal database frame too short: expected at least " + HEADER_BYTES + " bytes");
        }
        int header1 = Byte.toUnsignedInt(payload[0]);
        if (header1 != SignalDatabaseNodeFrame.HEADER_1) {
            throw new IllegalArgumentException("Invalid signal database frame header");
        }
        int packetType = Byte.toUnsignedInt(payload[1]);
        byte[] sourceId = Arrays.copyOfRange(payload, 2, 6);
        byte[] destinationId = Arrays.copyOfRange(payload, 6, 10);
        int dataLength = Short.toUnsignedInt(ByteBuffer.wrap(payload, 10, 2).order(byteOrder).getShort());
        if (dataLength < 2) {
            throw new IllegalArgumentException("Signal database frame data length must include the length field");
        }
        int contentLength = dataLength - 2;
        int expectedBytes = HEADER_BYTES + contentLength;
        if (payload.length < expectedBytes) {
            throw new IllegalArgumentException(
                "Signal database frame too short: expected " + expectedBytes + " bytes, got " + payload.length
            );
        }
        return new SignalDatabaseNodeFrame(
            packetType,
            sourceId,
            destinationId,
            Arrays.copyOfRange(payload, HEADER_BYTES, expectedBytes)
        );
    }

    public byte[] encode(SignalDatabaseNodeFrame frame) {
        byte[] content = frame.content();
        ByteBuffer buffer = ByteBuffer
            .allocate(HEADER_BYTES + content.length)
            .order(byteOrder);
        buffer.put((byte) SignalDatabaseNodeFrame.HEADER_1);
        buffer.put((byte) frame.packetType());
        buffer.put(frame.sourceId());
        buffer.put(frame.destinationId());
        buffer.putShort((short) (content.length + 2));
        buffer.put(content);
        return buffer.array();
    }

    public SignalDatabaseNodeFrame centralToSignalTrainFrame(byte[] content) {
        return new SignalDatabaseNodeFrame(
            SignalDatabaseNodeFrame.TRAIN_CONTENT,
            CENTRAL_SOURCE_ID,
            SIGNAL_SOURCE_ID,
            content
        );
    }
}
