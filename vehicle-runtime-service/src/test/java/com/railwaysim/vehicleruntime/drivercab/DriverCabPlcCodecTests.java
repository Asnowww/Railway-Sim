package com.railwaysim.vehicleruntime.drivercab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DriverCabPlcCodecTests {
    private final DriverCabPlcCodec codec = new DriverCabPlcCodec();

    @Test
    void roundTripsUpperComputerToPlcFrame() {
        DriverCabPlcOutputPacket expected = new DriverCabPlcOutputPacket(
            true, false, true, true, false, true, true, false, true, false
        );
        byte[] payload = codec.encodeOutput(expected);
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(payload).hasSize(26).startsWith(0x55, (byte) 0xaa, 0x55, (byte) 0xaa);
        assertThat(Short.toUnsignedInt(header.getShort(4))).isEqualTo(26);
        assertThat(Short.toUnsignedInt(header.getShort(6))).isEqualTo(2);
        assertThat(codec.decodeOutput(payload)).isEqualTo(expected);
    }
}
