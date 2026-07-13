package com.railwaysim.vehicle.drivercab;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.api.dto.DriverCabPlcGatewayRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DriverCabPlcGatewayEncoderTests {
    @Test
    void encodesStructuredCentralRequestInto9300PlcPacket() {
        byte[] payload = new DriverCabPlcGatewayEncoder().encode(new DriverCabPlcGatewayRequest(
            true, true, false, false, true, true, false, false,
            false, false, true, true, DriverCabDoorModeSwitch.AUTOMATIC,
            false, false, false, true, true, DriverCabDirectionHandleState.FORWARD,
            DriverCabMasterHandleState.TRACTION, 75, 0
        ));

        assertThat(payload).hasSize(46);
        assertThat(payload).startsWith(0x55, (byte) 0xaa, 0x55, (byte) 0xaa);
        assertThat(Byte.toUnsignedInt(payload[34]) & 0x80).isNotZero();
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Short.toUnsignedInt(buffer.getShort(38))).isEqualTo(1);
        assertThat(Short.toUnsignedInt(buffer.getShort(40))).isEqualTo(75);
    }
}
