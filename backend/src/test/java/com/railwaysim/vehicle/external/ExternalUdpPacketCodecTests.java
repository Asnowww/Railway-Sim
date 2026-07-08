package com.railwaysim.vehicle.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalUdpPacketCodecTests {

    @Test
    void encodesTwentyTrainCommandPacketAsLittleEndianDoubles() {
        ExternalUdpPacketCodec codec = new ExternalUdpPacketCodec();

        byte[] packet = codec.encodeCommands(List.of(
            new ExternalTrainCommand(1, 1, 80, 10, 1.2, ExternalTrainDirection.UP, 1),
            new ExternalTrainCommand(20, 2, 100, 20, 3.4, ExternalTrainDirection.DOWN, 2)
        ), 20);

        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(packet).hasSize(320);
        assertThat(buffer.getDouble(0)).isEqualTo(1);
        assertThat(buffer.getDouble(8)).isEqualTo(80);
        assertThat(buffer.getDouble(16)).isZero();
        assertThat(buffer.getDouble(19 * 16)).isEqualTo(2);
        assertThat(buffer.getDouble(19 * 16 + 8)).isEqualTo(100);
    }

    @Test
    void decodesTwentyTrainTelemetryPacketAsLittleEndianDoubles() {
        ExternalUdpPacketCodec codec = new ExternalUdpPacketCodec();
        ByteBuffer buffer = ByteBuffer
            .allocate(codec.telemetryPacketBytes(20))
            .order(ByteOrder.LITTLE_ENDIAN);
        for (int trainNo = 1; trainNo <= 20; trainNo++) {
            buffer.putDouble(trainNo * 0.1);
            buffer.putDouble(trainNo);
            buffer.putDouble(trainNo * 100.0);
        }

        List<ExternalTrainTelemetry> telemetry = codec.decodeTelemetry(buffer.array(), 20);

        assertThat(telemetry).hasSize(20);
        assertThat(telemetry.get(0).acceleration()).isEqualTo(0.1);
        assertThat(telemetry.get(0).speed()).isEqualTo(1.0);
        assertThat(telemetry.get(0).cumulativeDistance()).isEqualTo(100.0);
        assertThat(telemetry.get(19).acceleration()).isEqualTo(2.0);
        assertThat(telemetry.get(19).speed()).isEqualTo(20.0);
        assertThat(telemetry.get(19).cumulativeDistance()).isEqualTo(2_000.0);
    }
}
