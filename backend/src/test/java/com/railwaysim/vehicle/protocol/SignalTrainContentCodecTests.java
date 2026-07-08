package com.railwaysim.vehicle.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railwaysim.vehicle.external.ExternalTrainDirection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalTrainContentCodecTests {

    private final SignalTrainContentCodec codec = new SignalTrainContentCodec();

    @Test
    void encodesAndDecodesSingleTrainContentWithDynamicLoad() {
        TrainOperationalTelemetry telemetry = new TrainOperationalTelemetry(
            1,
            12.34,
            4_567.89,
            ExternalTrainDirection.UP,
            86_400,
            2.0,
            true,
            4,
            5
        );

        byte[] payload = codec.encode(List.of(telemetry));
        TrainOperationalTelemetry decoded = codec.decode(payload, 1).get(0);

        assertThat(payload).hasSize(SignalTrainContentCodec.BYTES_PER_TRAIN);
        assertThat(decoded.trainId()).isEqualTo("TR-001");
        assertThat(decoded.speedMetersPerSecond()).isEqualTo(12.34);
        assertThat(decoded.cumulativeDistanceMeters()).isEqualTo(4_567.89);
        assertThat(decoded.direction()).isEqualTo(ExternalTrainDirection.UP);
        assertThat(decoded.loadMassKg()).isEqualTo(86_400);
        assertThat(decoded.faultSpeedLimitMetersPerSecond()).isEqualTo(2.0);
        assertThat(decoded.emergencyBrakeApplied()).isTrue();
        assertThat(decoded.availableTractionCount()).isEqualTo(4);
        assertThat(decoded.availableBrakeCount()).isEqualTo(5);
    }

    @Test
    void usesLittleEndianForMultiByteNumericFields() {
        byte[] payload = codec.encode(List.of(new TrainOperationalTelemetry(
            2,
            1.0,
            2.0,
            ExternalTrainDirection.DOWN,
            0x01020304L,
            0,
            false,
            6,
            6
        )));

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(2);
        assertThat(buffer.getInt()).isEqualTo(100);
        assertThat(buffer.getInt()).isEqualTo(200);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(0xaa);
        assertThat(buffer.getInt()).isEqualTo(0x01020304);
    }

    @Test
    void rejectsPacketsShorterThanDeclaredTrainCount() {
        assertThatThrownBy(() -> codec.decode(new byte[SignalTrainContentCodec.BYTES_PER_TRAIN - 1], 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("packet too short");
    }
}
