package com.railwaysim.localnet.drivercab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class DriverCabScreenStreamDecoderTests {

    @Test
    void reassemblesFragmentedNetworkScreenFramesFromHeaderLength() {
        byte[] frame = frame(DriverCabScreenPacketCodec.NETWORK_SCREEN_BYTES, 570, 546);
        DriverCabScreenStreamDecoder decoder = new DriverCabScreenStreamDecoder(DriverCabRole.NETWORK_SCREEN);

        assertThat(decoder.feed(frame, 17)).isEmpty();
        byte[] remainder = java.util.Arrays.copyOfRange(frame, 17, frame.length);
        assertThat(decoder.feed(remainder, remainder.length)).containsExactly(frame);
    }

    @Test
    void usesSixtyEightWireBytesForSignalScreenHistoricalHeaderTotal() {
        byte[] frame = frame(
            DriverCabScreenPacketCodec.SIGNAL_SCREEN_BYTES,
            DriverCabScreenPacketCodec.SIGNAL_SCREEN_HEADER_TOTAL_BYTES,
            DriverCabScreenPacketCodec.SIGNAL_SCREEN_HEADER_DATA_BYTES
        );
        DriverCabScreenStreamDecoder decoder = new DriverCabScreenStreamDecoder(DriverCabRole.SIGNAL_SCREEN);

        assertThat(decoder.feed(frame, frame.length)).containsExactly(frame);
    }

    @Test
    void separatesNetworkEventFrameFromFollowingDisplayEcho() {
        byte[] event = frame(DriverCabScreenPacketCodec.NETWORK_SCREEN_INPUT_BYTES, 26, 2);
        byte[] echo = frame(DriverCabScreenPacketCodec.NETWORK_SCREEN_BYTES, 570, 546);
        byte[] combined = new byte[event.length + echo.length];
        System.arraycopy(event, 0, combined, 0, event.length);
        System.arraycopy(echo, 0, combined, event.length, echo.length);
        DriverCabScreenStreamDecoder decoder = new DriverCabScreenStreamDecoder(DriverCabRole.NETWORK_SCREEN);

        List<byte[]> frames = decoder.feed(combined, combined.length);

        assertThat(frames).containsExactly(event, echo);
    }

    private byte[] frame(int wireLength, int headerTotal, int dataLength) {
        ByteBuffer buffer = ByteBuffer.allocate(wireLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {0x55, (byte) 0xaa, 0x55, (byte) 0xaa});
        buffer.putShort((short) headerTotal);
        buffer.putShort((short) dataLength);
        return buffer.array();
    }
}
