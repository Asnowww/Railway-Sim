package com.railwaysim.vehicle.drivercab;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/** @deprecated LOCAL mode only. EXTERNAL_HTTP mode uses 9300's DriverCabPlcCodec. */
@Deprecated(forRemoval=true, since="2.0")
public class DriverCabPlcCodec {

    public static final int PLC_TO_UPPER_BYTES = 46;
    public static final int UPPER_TO_PLC_BYTES = 26;
    public static final int HEADER_BYTES = 24;
    private static final byte[] PLC_TO_UPPER_IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};
    private static final byte[] UPPER_TO_PLC_IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};

    private final ByteOrder byteOrder;

    public DriverCabPlcCodec() {
        this(ByteOrder.LITTLE_ENDIAN);
    }

    public DriverCabPlcCodec(ByteOrder byteOrder) {
        this.byteOrder = byteOrder == null ? ByteOrder.LITTLE_ENDIAN : byteOrder;
    }

    public DriverCabPlcInputPacket decodeInput(byte[] payload) {
        requireExactLength(payload, PLC_TO_UPPER_BYTES, "driver cab PLC input");
        validateHeader(payload, PLC_TO_UPPER_IDENTIFY, PLC_TO_UPPER_BYTES,
            PLC_TO_UPPER_BYTES - HEADER_BYTES, "driver cab PLC input");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        int doorModeCode = unsignedShort(buffer.getShort(32));
        int directionCode = unsignedShort(buffer.getShort(36));
        int masterHandleCode = unsignedShort(buffer.getShort(38));
        int tractionPercent = unsignedShort(buffer.getShort(40));
        int brakePercent = unsignedShort(buffer.getShort(42));
        requireAllowedCode("doorModeSwitchState", doorModeCode, 0, 1, 2);
        requireAllowedCode("directionHandleState", directionCode, 0, 1, 2);
        requireAllowedCode("masterHandleState", masterHandleCode, 0, 1, 2, 4);
        requirePercent("tractionNotchPercent", tractionPercent);
        requirePercent("brakeNotchPercent", brakePercent);
        return new DriverCabPlcInputPacket(
            bit(payload, 24, 1),
            bit(payload, 24, 5),
            bit(payload, 24, 6),
            bit(payload, 24, 7),
            bit(payload, 25, 0),
            bit(payload, 25, 2),
            bit(payload, 25, 3),
            bit(payload, 28, 0),
            bit(payload, 29, 0),
            bit(payload, 29, 1),
            bit(payload, 29, 2),
            bit(payload, 29, 3),
            DriverCabDoorModeSwitch.fromProtocolCode(doorModeCode),
            bit(payload, 34, 2),
            bit(payload, 34, 3),
            bit(payload, 34, 5),
            bit(payload, 34, 7),
            bit(payload, 35, 1),
            DriverCabDirectionHandleState.fromProtocolCode(directionCode),
            DriverCabMasterHandleState.fromProtocolCode(masterHandleCode),
            tractionPercent,
            brakePercent
        );
    }

    public byte[] encodeInput(DriverCabPlcInputPacket packet) {
        DriverCabPlcInputPacket input = packet == null ? DriverCabPlcInputPacket.neutral() : packet;
        ByteBuffer buffer = ByteBuffer.allocate(PLC_TO_UPPER_BYTES).order(byteOrder);
        writeHeader(buffer, PLC_TO_UPPER_IDENTIFY, PLC_TO_UPPER_BYTES, PLC_TO_UPPER_BYTES - HEADER_BYTES);
        setBit(buffer.array(), 24, 1, input.highVoltageClosedIndicator());
        setBit(buffer.array(), 24, 5, input.doorsClosedLockedIndicator());
        setBit(buffer.array(), 24, 6, input.networkFaultIndicator());
        setBit(buffer.array(), 24, 7, input.automaticTurnbackAvailable());
        setBit(buffer.array(), 25, 0, input.atoModeAvailable());
        setBit(buffer.array(), 25, 2, input.atoModeActive());
        setBit(buffer.array(), 25, 3, input.automaticTurnbackActive());
        setBit(buffer.array(), 28, 0, input.emergencyBrakeButtonLocked());
        setBit(buffer.array(), 29, 0, input.openLeftDoorFlag());
        setBit(buffer.array(), 29, 1, input.openRightDoorFlag());
        setBit(buffer.array(), 29, 2, input.closeLeftDoorFlag());
        setBit(buffer.array(), 29, 3, input.closeRightDoorFlag());
        buffer.putShort(32, (short) input.doorModeSwitchState().protocolCode());
        setBit(buffer.array(), 34, 2, input.modeUpgradeConfirmFlag());
        setBit(buffer.array(), 34, 3, input.modeDowngradeConfirmFlag());
        setBit(buffer.array(), 34, 5, input.automaticTurnbackFlag());
        setBit(buffer.array(), 34, 7, input.atoStartFlag());
        setBit(buffer.array(), 35, 1, input.keySwitchLocked());
        buffer.putShort(36, (short) input.directionHandleState().protocolCode());
        buffer.putShort(38, (short) input.masterHandleState().protocolCode());
        buffer.putShort(40, (short) input.tractionNotchPercent());
        buffer.putShort(42, (short) input.brakeNotchPercent());
        return buffer.array();
    }

    public DriverCabPlcOutputPacket decodeOutput(byte[] payload) {
        requireExactLength(payload, UPPER_TO_PLC_BYTES, "driver cab PLC output");
        validateHeader(payload, UPPER_TO_PLC_IDENTIFY, UPPER_TO_PLC_BYTES,
            UPPER_TO_PLC_BYTES - HEADER_BYTES, "driver cab PLC output");
        return new DriverCabPlcOutputPacket(
            bit(payload, 24, 1),
            bit(payload, 24, 2),
            bit(payload, 24, 4),
            bit(payload, 24, 5),
            bit(payload, 24, 6),
            bit(payload, 24, 7),
            bit(payload, 25, 0),
            bit(payload, 25, 1),
            bit(payload, 25, 2),
            bit(payload, 25, 3)
        );
    }

    public byte[] encodeOutput(DriverCabPlcOutputPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("driver cab PLC output packet is required");
        }
        ByteBuffer buffer = ByteBuffer.allocate(UPPER_TO_PLC_BYTES).order(byteOrder);
        writeHeader(buffer, UPPER_TO_PLC_IDENTIFY, UPPER_TO_PLC_BYTES, UPPER_TO_PLC_BYTES - HEADER_BYTES);
        setBit(buffer.array(), 24, 1, packet.highVoltageClosedIndicator());
        setBit(buffer.array(), 24, 2, packet.brakeReleaseFaultIndicator());
        setBit(buffer.array(), 24, 4, packet.doorOpenLamp());
        setBit(buffer.array(), 24, 5, packet.doorsClosedLockedLamp());
        setBit(buffer.array(), 24, 6, packet.networkFaultIndicator());
        setBit(buffer.array(), 24, 7, packet.automaticTurnbackAvailable());
        setBit(buffer.array(), 25, 0, packet.atoModeAvailable());
        setBit(buffer.array(), 25, 1, packet.washModeActive());
        setBit(buffer.array(), 25, 2, packet.atoModeActive());
        setBit(buffer.array(), 25, 3, packet.automaticTurnbackActive());
        return buffer.array();
    }

    private void writeHeader(ByteBuffer buffer, byte[] identify, int totalLength, int dataLength) {
        buffer.put(identify);
        buffer.putShort((short) totalLength);
        buffer.putShort((short) dataLength);
        LocalDateTime now = LocalDateTime.now();
        buffer.putShort((short) now.getYear());
        buffer.putShort((short) now.getMonthValue());
        buffer.putShort((short) now.getDayOfMonth());
        buffer.putShort((short) now.getHour());
        buffer.putShort((short) now.getMinute());
        buffer.putShort((short) now.getSecond());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
    }

    private void requireAllowedCode(String field, int actual, int... allowed) {
        for (int value : allowed) {
            if (actual == value) {
                return;
            }
        }
        throw new IllegalArgumentException(field + " has unsupported code: " + actual);
    }

    private void requirePercent(String field, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be in 0..100: " + value);
        }
    }

    private void requireExactLength(byte[] payload, int expectedLength, String label) {
        if (payload == null || payload.length != expectedLength) {
            throw new IllegalArgumentException(label + " length is invalid: expected " + expectedLength + " bytes");
        }
    }

    private void validateHeader(byte[] payload, byte[] expected, int totalLength, int dataLength, String label) {
        validateIdentify(payload, expected, label);
        ByteBuffer header = ByteBuffer.wrap(payload).order(byteOrder);
        if (Short.toUnsignedInt(header.getShort(4)) != totalLength) {
            throw new IllegalArgumentException(label + " total length is invalid");
        }
        if (Short.toUnsignedInt(header.getShort(6)) != dataLength) {
            throw new IllegalArgumentException(label + " data length is invalid");
        }
    }

    private void validateIdentify(byte[] payload, byte[] expected, String label) {
        for (int i = 0; i < expected.length; i++) {
            if (payload[i] != expected[i]) {
                throw new IllegalArgumentException("Invalid " + label + " identify header");
            }
        }
    }

    private boolean bit(byte[] payload, int byteOffset, int bitOffset) {
        return (Byte.toUnsignedInt(payload[byteOffset]) & (1 << bitOffset)) != 0;
    }

    private void setBit(byte[] payload, int byteOffset, int bitOffset, boolean enabled) {
        int value = Byte.toUnsignedInt(payload[byteOffset]);
        if (enabled) {
            value |= 1 << bitOffset;
        } else {
            value &= ~(1 << bitOffset);
        }
        payload[byteOffset] = (byte) value;
    }

    private int unsignedShort(short value) {
        return Short.toUnsignedInt(value);
    }
}
