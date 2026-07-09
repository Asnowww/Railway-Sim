package com.railwaysim.localnet.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class SignalDatabaseNodeFrameCodecTests {

    private final SignalDatabaseNodeFrameCodec codec = new SignalDatabaseNodeFrameCodec();

    @Test
    void encodesAndDecodesLittleEndianLengthFrame() {
        byte[] content = {0x01, 0x02, 0x03};
        SignalDatabaseNodeFrame frame = codec.centralToSignalTrainFrame(content);

        byte[] payload = codec.encode(frame);
        SignalDatabaseNodeFrame decoded = codec.decode(payload);

        assertThat(payload[0]).isEqualTo((byte) 0xff);
        assertThat(payload[1]).isEqualTo((byte) 0xf0);
        assertThat(ByteBuffer.wrap(payload, 10, 2).order(ByteOrder.LITTLE_ENDIAN).getShort()).isEqualTo((short) 5);
        assertThat(decoded.content()).containsExactly(content);
        assertThat(decoded.sourceId()).containsExactly(SignalDatabaseNodeFrameCodec.CENTRAL_SOURCE_ID);
        assertThat(decoded.destinationId()).containsExactly(SignalDatabaseNodeFrameCodec.SIGNAL_SOURCE_ID);
    }

    @Test
    void rejectsInvalidHeaderAndShortFrames() {
        assertThatThrownBy(() -> codec.decode(new byte[] {0x00, (byte) 0xf0}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too short");

        byte[] payload = codec.encode(codec.centralToSignalTrainFrame(new byte[] {0x01}));
        payload[0] = 0;

        assertThatThrownBy(() -> codec.decode(payload))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid");
    }
}
