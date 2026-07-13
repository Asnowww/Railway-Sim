package com.railwaysim.localnet.drivercab;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.vehicle.SignalCabDisplayState;
import com.railwaysim.train.TrainState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DriverCabScreenPacketCodecTests {

    private final DriverCabScreenPacketCodec codec = new DriverCabScreenPacketCodec();

    @Test
    void encodesNetworkScreenWithStableLengthAndHeader() {
        byte[] payload = codec.encodeNetworkScreen(
            train(),
            SignalCabDisplayState.fromAuthorityOnly("TR-001", 20, false, false, false),
            List.of(powerSection())
        );

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(payload).hasSize(DriverCabScreenPacketCodec.NETWORK_SCREEN_BYTES);
        assertThat(payload).startsWith(0x55, (byte) 0xaa, 0x55, (byte) 0xaa);
        assertThat(Short.toUnsignedInt(buffer.getShort(4))).isEqualTo(570);
        assertThat(Short.toUnsignedInt(buffer.getShort(6))).isEqualTo(546);
        assertThat(buffer.getFloat(40)).isEqualTo(12.5f);
        assertThat(Short.toUnsignedInt(buffer.getShort(50))).isEqualTo(1480);
        assertThat(Short.toUnsignedInt(buffer.getShort(568))).isEqualTo(1);
        DriverCabScreenPacketCodec.NetworkScreenFrame decoded = codec.decodeNetworkScreen(payload);
        assertThat(decoded.speedMetersPerSecond()).isEqualTo(12.5f);
        assertThat(decoded.trainNumber()).isEqualTo(1);
        assertThat(decoded.rawPayload()).isEqualTo(payload);
    }

    @Test
    void encodesSignalScreenWithStableLengthAndModeFields() {
        byte[] payload = codec.encodeSignalScreen(
            train(),
            SignalCabDisplayState.fromAuthorityOnly("TR-001", 18, false, false, false)
        );

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(payload).hasSize(DriverCabScreenPacketCodec.SIGNAL_SCREEN_BYTES);
        assertThat(payload).startsWith(0x55, (byte) 0xaa, 0x55, (byte) 0xaa);
        assertThat(Short.toUnsignedInt(buffer.getShort(4))).isEqualTo(62);
        assertThat(Short.toUnsignedInt(buffer.getShort(6))).isEqualTo(42);
        assertThat(buffer.get(42)).isZero();
        assertThat(buffer.getFloat(44)).isEqualTo(45.0f);
        assertThat(Short.toUnsignedInt(buffer.getShort(52))).isZero();
        assertThat(Short.toUnsignedInt(buffer.getShort(54))).isEqualTo(65);
        assertThat(Short.toUnsignedInt(buffer.getShort(62))).isEqualTo(1);
        assertThat(buffer.getFloat(64)).isEqualTo(1_000_000.0f);
        DriverCabScreenPacketCodec.SignalScreenFrame decoded = codec.decodeSignalScreen(payload);
        assertThat(decoded.headerTotalLength()).isEqualTo(62);
        assertThat(decoded.speedKilometersPerHour()).isEqualTo(45.0f);
        assertThat(decoded.trainNumber()).isEqualTo(1);
    }

    @Test
    void decodesNetworkScreenTractionCutRequest() {
        ByteBuffer buffer = ByteBuffer.allocate(DriverCabScreenPacketCodec.NETWORK_SCREEN_INPUT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {0x55, (byte) 0xaa, 0x55, (byte) 0xaa});
        buffer.putShort((short) 26).putShort((short) 2);
        buffer.put(24, (byte) 0b0010_0101);

        assertThat(codec.decodeTractionCutMask(buffer.array())).isEqualTo(0b0010_0101);
    }

    private TrainState train() {
        return new TrainState(
            "TR-001",
            "demo-line-1",
            "TR-001",
            500,
            12.5,
            120,
            500,
            380,
            0.35,
            "RUNNING",
            "ATO",
            false,
            "CLOSED_LOCKED",
            "TRACTION",
            "IDLE",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "CRUISE",
            "TEST",
            20,
            250,
            40,
            55,
            0.4,
            80_000,
            0,
            0,
            500,
            375_000,
            0,
            0.08,
            0,
            "OK"
        );
    }

    private PowerSectionState powerSection() {
        return new PowerSectionState(
            "P01",
            "Power 01",
            "SS01",
            "F01",
            0,
            2500,
            1480,
            200,
            "ENERGIZED",
            296_000,
            0,
            0,
            0,
            3_000_000,
            "DOUBLE_END",
            "CLOSED",
            "AVAILABLE",
            "CLOSED",
            "NORMAL",
            "NONE",
            "UNLOCKED",
            "GOOD",
            1480,
            200,
            296_000,
            0,
            0,
            "MATCHED",
            "OK",
            "NORMAL",
            "",
            List.of("TR-001"),
            "GOOD",
            Instant.now()
        );
    }
}
