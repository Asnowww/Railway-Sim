package com.railwaysim.vehicle.drivercab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DriverCabPlcCodecTests {

    private final DriverCabPlcCodec codec = new DriverCabPlcCodec();

    @Test
    void encodesAndDecodesPlcInputPacket() {
        DriverCabPlcInputPacket packet = new DriverCabPlcInputPacket(
            true,
            true,
            false,
            true,
            true,
            true,
            false,
            false,
            false,
            true,
            false,
            false,
            DriverCabDoorModeSwitch.MANUAL,
            true,
            false,
            true,
            true,
            true,
            DriverCabDirectionHandleState.BACKWARD,
            DriverCabMasterHandleState.FAST_BRAKE,
            75,
            40
        );

        byte[] payload = codec.encodeInput(packet);
        DriverCabPlcInputPacket decoded = codec.decodeInput(payload);

        assertThat(payload).hasSize(DriverCabPlcCodec.PLC_TO_UPPER_BYTES);
        assertThat(payload[0]).isEqualTo((byte) 0xaa);
        assertThat(payload[1]).isEqualTo((byte) 0x55);
        assertThat(decoded.doorModeSwitchState()).isEqualTo(DriverCabDoorModeSwitch.MANUAL);
        assertThat(decoded.directionHandleState()).isEqualTo(DriverCabDirectionHandleState.BACKWARD);
        assertThat(decoded.masterHandleState()).isEqualTo(DriverCabMasterHandleState.FAST_BRAKE);
        assertThat(decoded.atoStartFlag()).isTrue();
        assertThat(decoded.modeUpgradeConfirmFlag()).isTrue();
        assertThat(decoded.automaticTurnbackFlag()).isTrue();
        assertThat(decoded.openRightDoorFlag()).isTrue();
        assertThat(decoded.tractionNotchPercent()).isEqualTo(75);
        assertThat(decoded.brakeNotchPercent()).isEqualTo(40);
        assertThat(decoded.emergencyBrakeRequested()).isTrue();
    }

    @Test
    void encodesAndDecodesUpperOutputPacket() {
        DriverCabPlcOutputPacket packet = new DriverCabPlcOutputPacket(
            true,
            false,
            true,
            false,
            true,
            true,
            true,
            false,
            true,
            false
        );

        byte[] payload = codec.encodeOutput(packet);
        DriverCabPlcOutputPacket decoded = codec.decodeOutput(payload);

        assertThat(payload).hasSize(DriverCabPlcCodec.UPPER_TO_PLC_BYTES);
        assertThat(payload[0]).isEqualTo((byte) 0x55);
        assertThat(payload[1]).isEqualTo((byte) 0xaa);
        assertThat(decoded.highVoltageClosedIndicator()).isTrue();
        assertThat(decoded.doorOpenLamp()).isTrue();
        assertThat(decoded.doorsClosedLockedLamp()).isFalse();
        assertThat(decoded.networkFaultIndicator()).isTrue();
        assertThat(decoded.atoModeAvailable()).isTrue();
        assertThat(decoded.atoModeActive()).isTrue();
    }

    @Test
    void rejectsInvalidHeader() {
        byte[] payload = codec.encodeInput(DriverCabPlcInputPacket.neutral());
        payload[0] = 0;

        assertThatThrownBy(() -> codec.decodeInput(payload))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("identify header");
    }

    @Test
    void rejectsOutOfRangeNotchInsteadOfClamping() {
        byte[] payload = codec.encodeInput(DriverCabPlcInputPacket.neutral());
        payload[40] = 101;
        payload[41] = 0;

        assertThatThrownBy(() -> codec.decodeInput(payload))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tractionNotchPercent must be in 0..100");
    }
}
