package com.railwaysim.vehicleruntime.drivercab;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DriverCabPlcCodec {

    public static final int PLC_TO_UPPER_BYTES = 46;
    public static final int UPPER_TO_PLC_BYTES = 26;
    public static final int HEADER_BYTES = 24;
    private static final byte[] IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};

    private final ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;

    public DriverCabPlcInputPacket decodeInput(byte[] payload) {
        requireExactLength(payload, PLC_TO_UPPER_BYTES, "driver cab PLC input");
        validateHeader(payload, PLC_TO_UPPER_BYTES, PLC_TO_UPPER_BYTES - HEADER_BYTES,
            "driver cab PLC input");
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
            bit(payload, 24, 1), bit(payload, 24, 5), bit(payload, 24, 6), bit(payload, 24, 7),
            bit(payload, 25, 0), bit(payload, 25, 2), bit(payload, 25, 3), bit(payload, 28, 0),
            bit(payload, 29, 0), bit(payload, 29, 1), bit(payload, 29, 2), bit(payload, 29, 3),
            DriverCabDoorModeSwitch.fromProtocolCode(doorModeCode),
            bit(payload, 34, 2), bit(payload, 34, 3), bit(payload, 34, 5), bit(payload, 34, 7),
            bit(payload, 35, 1),
            DriverCabDirectionHandleState.fromProtocolCode(directionCode),
            DriverCabMasterHandleState.fromProtocolCode(masterHandleCode),
            tractionPercent, brakePercent
        );
    }

    public byte[] encodeInput(DriverCabPlcInputPacket packet) {
        DriverCabPlcInputPacket input = packet == null ? DriverCabPlcInputPacket.neutral() : packet;
        byte[] payload = new byte[PLC_TO_UPPER_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        buffer.put(IDENTIFY).putShort((short) PLC_TO_UPPER_BYTES)
            .putShort((short) (PLC_TO_UPPER_BYTES - HEADER_BYTES));
        setBit(payload, 24, 1, input.highVoltageClosedIndicator());
        setBit(payload, 24, 5, input.doorsClosedLockedIndicator());
        setBit(payload, 24, 6, input.networkFaultIndicator());
        setBit(payload, 24, 7, input.automaticTurnbackAvailable());
        setBit(payload, 25, 0, input.atoModeAvailable());
        setBit(payload, 25, 2, input.atoModeActive());
        setBit(payload, 25, 3, input.automaticTurnbackActive());
        setBit(payload, 28, 0, input.emergencyBrakeButtonLocked());
        setBit(payload, 29, 0, input.openLeftDoorFlag());
        setBit(payload, 29, 1, input.openRightDoorFlag());
        setBit(payload, 29, 2, input.closeLeftDoorFlag());
        setBit(payload, 29, 3, input.closeRightDoorFlag());
        buffer.putShort(32, (short) input.doorModeSwitchState().protocolCode());
        setBit(payload, 34, 2, input.modeUpgradeConfirmFlag());
        setBit(payload, 34, 3, input.modeDowngradeConfirmFlag());
        setBit(payload, 34, 5, input.automaticTurnbackFlag());
        setBit(payload, 34, 7, input.atoStartFlag());
        setBit(payload, 35, 1, input.keySwitchLocked());
        buffer.putShort(36, (short) input.directionHandleState().protocolCode());
        buffer.putShort(38, (short) input.masterHandleState().protocolCode());
        buffer.putShort(40, (short) clampPercent(input.tractionNotchPercent()));
        buffer.putShort(42, (short) clampPercent(input.brakeNotchPercent()));
        return payload;
    }

    public DriverCabPlcOutputPacket decodeOutput(byte[] payload) {
        requireExactLength(payload, UPPER_TO_PLC_BYTES, "driver cab PLC output");
        validateHeader(payload, UPPER_TO_PLC_BYTES, UPPER_TO_PLC_BYTES - HEADER_BYTES,
            "driver cab PLC output");
        return new DriverCabPlcOutputPacket(
            bit(payload, 24, 1), bit(payload, 24, 2), bit(payload, 24, 4), bit(payload, 24, 5),
            bit(payload, 24, 6), bit(payload, 24, 7), bit(payload, 25, 0), bit(payload, 25, 1),
            bit(payload, 25, 2), bit(payload, 25, 3)
        );
    }

    public byte[] encodeOutput(DriverCabPlcOutputPacket packet) {
        if (packet == null) throw new IllegalArgumentException("driver cab PLC output is required");
        byte[] payload = new byte[UPPER_TO_PLC_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        buffer.put(IDENTIFY).putShort((short) UPPER_TO_PLC_BYTES)
            .putShort((short) (UPPER_TO_PLC_BYTES - HEADER_BYTES));
        setBit(payload, 24, 1, packet.highVoltageClosedIndicator());
        setBit(payload, 24, 2, packet.brakeReleaseFaultIndicator());
        setBit(payload, 24, 4, packet.doorOpenLamp());
        setBit(payload, 24, 5, packet.doorsClosedLockedLamp());
        setBit(payload, 24, 6, packet.networkFaultIndicator());
        setBit(payload, 24, 7, packet.automaticTurnbackAvailable());
        setBit(payload, 25, 0, packet.atoModeAvailable());
        setBit(payload, 25, 1, packet.washModeActive());
        setBit(payload, 25, 2, packet.atoModeActive());
        setBit(payload, 25, 3, packet.automaticTurnbackActive());
        return payload;
    }

    private boolean bit(byte[] payload, int byteIndex, int bitIndex) {
        return (payload[byteIndex] & (1 << bitIndex)) != 0;
    }

    private void setBit(byte[] payload, int byteIndex, int bitIndex, boolean value) {
        if (value) payload[byteIndex] |= (byte) (1 << bitIndex);
        else payload[byteIndex] &= (byte) ~(1 << bitIndex);
    }

    private int unsignedShort(short value) { return value & 0xFFFF; }
    private int clampPercent(int value) { return Math.max(0, Math.min(100, value)); }

    private void validateHeader(byte[] payload, int totalLength, int dataLength, String label) {
        for (int i = 0; i < IDENTIFY.length; i++) {
            if (payload[i] != IDENTIFY[i]) {
                throw new IllegalArgumentException(label + " identify bytes are invalid");
            }
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(byteOrder);
        if (unsignedShort(header.getShort(4)) != totalLength) {
            throw new IllegalArgumentException(label + " total length is invalid");
        }
        if (unsignedShort(header.getShort(6)) != dataLength) {
            throw new IllegalArgumentException(label + " data length is invalid");
        }
    }

    private void requireExactLength(byte[] payload, int expected, String label) {
        if (payload == null || payload.length != expected)
            throw new IllegalArgumentException(label + " length is invalid: expected " + expected + " bytes");
    }

    private void requireAllowedCode(String field, int actual, int... allowed) {
        for (int v : allowed) { if (actual == v) return; }
        throw new IllegalArgumentException(field + " has unsupported code: " + actual);
    }

    private void requirePercent(String field, int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be in 0..100: " + value);
    }
}
