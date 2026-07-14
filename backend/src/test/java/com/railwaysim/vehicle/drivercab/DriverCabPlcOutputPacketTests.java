package com.railwaysim.vehicle.drivercab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DriverCabPlcOutputPacketTests {

    @Test
    void encodesPhysicalPlcOutputAsTwentySixByteLittleEndianFrame() {
        byte[] payload = new DriverCabPlcOutputPacket(
            true, false, true, false, true, false, true, false, true, false
        ).toBytes();

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(payload).hasSize(26);
        assertThat(payload).startsWith(0x55, (byte) 0xaa, 0x55, (byte) 0xaa);
        assertThat(Short.toUnsignedInt(buffer.getShort(4))).isEqualTo(26);
        assertThat(Short.toUnsignedInt(buffer.getShort(6))).isEqualTo(2);
        assertThat(Byte.toUnsignedInt(payload[24])).isEqualTo(0b0101_0010);
        assertThat(Byte.toUnsignedInt(payload[25])).isEqualTo(0b0000_0101);
    }
}
