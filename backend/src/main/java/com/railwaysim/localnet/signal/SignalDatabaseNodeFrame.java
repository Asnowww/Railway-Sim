package com.railwaysim.localnet.signal;

import java.util.Arrays;

public record SignalDatabaseNodeFrame(
    int packetType,
    byte[] sourceId,
    byte[] destinationId,
    byte[] content
) {
    public static final int HEADER_1 = 0xff;
    public static final int TRAIN_CONTENT = 0xf0;
    public static final int CAB_SWITCH_CONTENT = 0xf1;

    public SignalDatabaseNodeFrame {
        if (packetType != TRAIN_CONTENT && packetType != CAB_SWITCH_CONTENT) {
            throw new IllegalArgumentException("Unsupported signal database packet type: " + packetType);
        }
        sourceId = normalizedFourBytes(sourceId, "sourceId");
        destinationId = normalizedFourBytes(destinationId, "destinationId");
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
    }

    public boolean trainContentFrame() {
        return packetType == TRAIN_CONTENT;
    }

    public boolean cabSwitchFrame() {
        return packetType == CAB_SWITCH_CONTENT;
    }

    private static byte[] normalizedFourBytes(byte[] value, String label) {
        if (value == null || value.length != 4) {
            throw new IllegalArgumentException(label + " must be 4 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }
}
