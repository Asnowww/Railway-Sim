package com.railwaysim.vehicle.drivercab;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

public record DriverCabPlcOutputPacket(
    boolean highVoltageClosedIndicator,
    boolean brakeReleaseFaultIndicator,
    boolean doorOpenLamp,
    boolean doorsClosedLockedLamp,
    boolean networkFaultIndicator,
    boolean automaticTurnbackAvailable,
    boolean atoModeAvailable,
    boolean washModeActive,
    boolean atoModeActive,
    boolean automaticTurnbackActive
) {
    public static final int BYTES = 26;

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {0x55, (byte) 0xaa, 0x55, (byte) 0xaa});
        buffer.putShort((short) BYTES);
        buffer.putShort((short) (BYTES - 24));
        LocalDateTime now = LocalDateTime.now();
        buffer.putShort((short) now.getYear());
        buffer.putShort((short) now.getMonthValue());
        buffer.putShort((short) now.getDayOfMonth());
        buffer.putShort((short) now.getHour());
        buffer.putShort((short) now.getMinute());
        buffer.putShort((short) now.getSecond());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        byte[] bytes = buffer.array();
        setBit(bytes, 24, 1, highVoltageClosedIndicator);
        setBit(bytes, 24, 2, brakeReleaseFaultIndicator);
        setBit(bytes, 24, 4, doorOpenLamp);
        setBit(bytes, 24, 5, doorsClosedLockedLamp);
        setBit(bytes, 24, 6, networkFaultIndicator);
        setBit(bytes, 24, 7, automaticTurnbackAvailable);
        setBit(bytes, 25, 0, atoModeAvailable);
        setBit(bytes, 25, 1, washModeActive);
        setBit(bytes, 25, 2, atoModeActive);
        setBit(bytes, 25, 3, automaticTurnbackActive);
        return bytes;
    }

    private void setBit(byte[] payload, int byteOffset, int bitOffset, boolean enabled) {
        if (enabled) {
            payload[byteOffset] = (byte) (Byte.toUnsignedInt(payload[byteOffset]) | (1 << bitOffset));
        }
    }
}
