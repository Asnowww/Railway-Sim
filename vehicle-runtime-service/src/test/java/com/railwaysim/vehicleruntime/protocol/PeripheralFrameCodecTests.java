package com.railwaysim.vehicleruntime.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PeripheralFrameCodecTests {
    private static final byte[] CONTRACT_FRAME = HexFormat.of().parseHex(
        "5253494D0101140004030201060004000000010054522D30303155AA55AA92F61303"
    );
    private final PeripheralFrameCodec codec = new PeripheralFrameCodec();

    @Test
    void matchesCentralBinaryContract() {
        PeripheralFrame frame = new PeripheralFrame(
            PeripheralChannel.PLC_INPUT, 0x01020304, 1, "TR-001",
            HexFormat.of().parseHex("55AA55AA")
        );
        assertThat(codec.encode(frame)).isEqualTo(CONTRACT_FRAME);
        assertThat(codec.decode(CONTRACT_FRAME).payload()).containsExactly(0x55, (byte) 0xaa, 0x55, (byte) 0xaa);
    }

    @Test
    void rejectsCrcMismatch() {
        byte[] corrupted = CONTRACT_FRAME.clone();
        corrupted[27] ^= 1;
        assertThatThrownBy(() -> codec.decode(corrupted)).hasMessageContaining("CRC32");
    }
}
