package com.railwaysim.vehicle.drivercab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void decodeSnapshotRoundTripsWithEncodedFrame() {
        DriverCabPlcGatewayEncoder encoder = new DriverCabPlcGatewayEncoder();
        DriverCabPlcGatewayRequest request = new DriverCabPlcGatewayRequest(
            true, true, false, false, true, true, false, false,
            false, false, true, true, DriverCabDoorModeSwitch.AUTOMATIC,
            false, false, false, true, true, DriverCabDirectionHandleState.FORWARD,
            DriverCabMasterHandleState.TRACTION, 75, 0
        );

        DriverCabStateSnapshot snapshot = encoder.decodeSnapshot(
            encoder.encode(request), DriverCabControlSource.PHYSICAL
        );

        assertThat(snapshot.doorModeSwitchState()).isEqualTo(DriverCabDoorModeSwitch.AUTOMATIC);
        assertThat(snapshot.directionHandleState()).isEqualTo(DriverCabDirectionHandleState.FORWARD);
        assertThat(snapshot.masterHandleState()).isEqualTo(DriverCabMasterHandleState.TRACTION);
        assertThat(snapshot.tractionNotchPercent()).isEqualTo(75);
        assertThat(snapshot.brakeNotchPercent()).isZero();
        assertThat(snapshot.atoStartFlag()).isTrue();
        assertThat(snapshot.keySwitchLocked()).isTrue();
        assertThat(snapshot.source()).isEqualTo(DriverCabControlSource.PHYSICAL);
    }

    @Test
    void toSnapshotMapsStructuredRequestAndTagsFrontendSource() {
        DriverCabPlcGatewayRequest request = new DriverCabPlcGatewayRequest(
            true, true, false, false, true, false, false, false,
            false, false, true, true, DriverCabDoorModeSwitch.SEMI_AUTOMATIC,
            false, false, false, false, true, DriverCabDirectionHandleState.BACKWARD,
            DriverCabMasterHandleState.BRAKE, 0, 40
        );

        DriverCabStateSnapshot snapshot = new DriverCabPlcGatewayEncoder()
            .toSnapshot(request, DriverCabControlSource.FRONTEND);

        assertThat(snapshot.directionHandleState()).isEqualTo(DriverCabDirectionHandleState.BACKWARD);
        assertThat(snapshot.masterHandleState()).isEqualTo(DriverCabMasterHandleState.BRAKE);
        assertThat(snapshot.brakeNotchPercent()).isEqualTo(40);
        assertThat(snapshot.source()).isEqualTo(DriverCabControlSource.FRONTEND);
    }

    @Test
    void decodeSnapshotRejectsWrongLength() {
        assertThatThrownBy(() -> new DriverCabPlcGatewayEncoder()
            .decodeSnapshot(new byte[10], DriverCabControlSource.PHYSICAL))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
