package com.railwaysim.signal.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.vehicle.external.ExternalTrainDirection;
import org.junit.jupiter.api.Test;

class SignalTrainLifecycleCommandCodecTests {

    private final SignalTrainLifecycleCommandCodec codec = new SignalTrainLifecycleCommandCodec();

    @Test
    void encodesAndDecodesAddTrainPacketAsLittleEndian() {
        SignalTrainLifecycleCommand command = SignalTrainLifecycleCommand.add(
            java.util.List.of(SignalTrainLifecycleTrainSpec.add(1, 258, 12_345, ExternalTrainDirection.UP))
        );

        byte[] payload = codec.encode(command);
        SignalTrainLifecycleCommand decoded = codec.decode(payload);

        assertThat(payload).containsExactly(
            (byte) 0xff,
            (byte) 0xf1,
            0x01,
            0x01,
            0x01,
            0x00,
            0x02,
            0x01,
            0x39,
            0x30,
            0x00,
            0x00,
            0x55,
            0x00
        );
        assertThat(decoded.action()).isEqualTo(SignalTrainLifecycleAction.ADD);
        assertThat(decoded.trains()).hasSize(1);
        assertThat(decoded.trains().get(0).trainId()).isEqualTo("TR-001");
        assertThat(decoded.trains().get(0).linkId()).isEqualTo(258);
        assertThat(decoded.trains().get(0).offsetMeters()).isEqualTo(12_345);
        assertThat(decoded.trains().get(0).direction()).isEqualTo(ExternalTrainDirection.UP);
    }

    @Test
    void encodesAndDecodesDeleteAndClearPackets() {
        SignalTrainLifecycleCommand deleteCommand = SignalTrainLifecycleCommand.delete(
            java.util.List.of(SignalTrainLifecycleTrainSpec.delete(20))
        );

        SignalTrainLifecycleCommand decodedDelete = codec.decode(codec.encode(deleteCommand));
        SignalTrainLifecycleCommand decodedClear = codec.decode(codec.encode(SignalTrainLifecycleCommand.clear()));

        assertThat(decodedDelete.action()).isEqualTo(SignalTrainLifecycleAction.DELETE);
        assertThat(decodedDelete.trains()).singleElement()
            .extracting(SignalTrainLifecycleTrainSpec::trainId)
            .isEqualTo("TR-020");
        assertThat(decodedClear.action()).isEqualTo(SignalTrainLifecycleAction.CLEAR);
        assertThat(decodedClear.trains()).isEmpty();
    }
}
