package com.railwaysim.localnet.drivercab;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles screen TCP frames, including the MMI's historical mismatch where
 * the wire payload is 68 bytes while the header totalLen remains 62.
 */
final class DriverCabScreenStreamDecoder {

    private static final byte[] IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};
    private static final int HEADER_PREFIX_BYTES = 8;
    private static final int MAX_FRAME_BYTES = 4096;

    private final DriverCabRole role;
    private byte[] pending = new byte[0];

    DriverCabScreenStreamDecoder(DriverCabRole role) {
        this.role = role;
    }

    List<byte[]> feed(byte[] bytes, int length) {
        if (bytes == null || length <= 0) {
            return List.of();
        }
        int acceptedLength = Math.min(length, bytes.length);
        byte[] combined = Arrays.copyOf(pending, pending.length + acceptedLength);
        System.arraycopy(bytes, 0, combined, pending.length, acceptedLength);
        pending = combined;

        List<byte[]> frames = new ArrayList<>();
        while (true) {
            int identifyAt = identifyOffset(pending);
            if (identifyAt < 0) {
                pending = trailingIdentifyPrefix(pending);
                return frames;
            }
            if (identifyAt > 0) {
                pending = Arrays.copyOfRange(pending, identifyAt, pending.length);
            }
            if (pending.length < HEADER_PREFIX_BYTES) {
                return frames;
            }
            int headerTotal = Short.toUnsignedInt(
                ByteBuffer.wrap(pending, 4, 2).order(ByteOrder.LITTLE_ENDIAN).getShort()
            );
            int wireLength = wireLength(headerTotal);
            if (wireLength < HEADER_PREFIX_BYTES || wireLength > MAX_FRAME_BYTES) {
                pending = Arrays.copyOfRange(pending, 1, pending.length);
                continue;
            }
            if (pending.length < wireLength) {
                return frames;
            }
            frames.add(Arrays.copyOfRange(pending, 0, wireLength));
            pending = Arrays.copyOfRange(pending, wireLength, pending.length);
        }
    }

    private int wireLength(int headerTotal) {
        if (
            role == DriverCabRole.SIGNAL_SCREEN
                && headerTotal == DriverCabScreenPacketCodec.SIGNAL_SCREEN_HEADER_TOTAL_BYTES
        ) {
            return DriverCabScreenPacketCodec.SIGNAL_SCREEN_BYTES;
        }
        return headerTotal;
    }

    private int identifyOffset(byte[] bytes) {
        for (int offset = 0; offset <= bytes.length - IDENTIFY.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < IDENTIFY.length; index++) {
                if (bytes[offset + index] != IDENTIFY[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private byte[] trailingIdentifyPrefix(byte[] bytes) {
        int keep = Math.min(IDENTIFY.length - 1, bytes.length);
        for (int length = keep; length > 0; length--) {
            boolean matches = true;
            for (int index = 0; index < length; index++) {
                if (bytes[bytes.length - length + index] != IDENTIFY[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return Arrays.copyOfRange(bytes, bytes.length - length, bytes.length);
            }
        }
        return new byte[0];
    }
}
