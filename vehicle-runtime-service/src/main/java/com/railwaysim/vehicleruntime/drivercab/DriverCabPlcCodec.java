package com.railwaysim.vehicleruntime.drivercab;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DriverCabPlcCodec {

    private static final int PLC_TO_UPPER_BYTES = 46;
    private static final int PLC_TO_UPPER_IDENTIFY = 0xAA;

    private final ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;

    public DriverCabPlcInputPacket decodeInput(byte[] payload) {
        requireMinLength(payload, PLC_TO_UPPER_BYTES, "driver cab PLC input");
        validateIdentify(payload, PLC_TO_UPPER_IDENTIFY, "driver cab PLC input");
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
            bit(payload, 25, 0), bit(payload, 28, 0), bit(payload, 28, 1), bit(payload, 28, 2),
            bit(payload, 28, 4), bit(payload, 28, 5), bit(payload, 29, 1), bit(payload, 29, 2),
            DriverCabDoorModeSwitch.fromProtocolCode(doorModeCode),
            bit(payload, 34, 2), bit(payload, 34, 3), bit(payload, 34, 5), bit(payload, 34, 7),
            bit(payload, 35, 1),
            DriverCabDirectionHandleState.fromProtocolCode(directionCode),
            DriverCabMasterHandleState.fromProtocolCode(masterHandleCode),
            tractionPercent, brakePercent
        );
    }

    public byte[] encodeInput(DriverCabPlcInputPacket packet) {
        byte[] payload = new byte[PLC_TO_UPPER_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        payload[0] = (byte) PLC_TO_UPPER_IDENTIFY;
        setBit(payload, 24, 1, packet.highVoltageClosedIndicator());
        setBit(payload, 24, 5, packet.doorsClosedLockedIndicator());
        setBit(payload, 24, 6, packet.networkFaultIndicator());
        setBit(payload, 24, 7, packet.automaticTurnbackAvailable());
        setBit(payload, 25, 0, packet.atoModeAvailable());
        setBit(payload, 28, 0, packet.atoModeActive());
        setBit(payload, 28, 1, packet.automaticTurnbackActive());
        setBit(payload, 28, 2, packet.emergencyBrakeButtonLocked());
        setBit(payload, 28, 4, packet.openLeftDoorFlag());
        setBit(payload, 28, 5, packet.openRightDoorFlag());
        setBit(payload, 29, 1, packet.closeLeftDoorFlag());
        setBit(payload, 29, 2, packet.closeRightDoorFlag());
        buffer.putShort(32, (short) packet.doorModeSwitchState().protocolCode());
        setBit(payload, 34, 2, packet.modeUpgradeConfirmFlag());
        setBit(payload, 34, 3, packet.modeDowngradeConfirmFlag());
        setBit(payload, 34, 5, packet.automaticTurnbackFlag());
        setBit(payload, 34, 7, packet.atoStartFlag());
        setBit(payload, 35, 1, packet.keySwitchLocked());
        buffer.putShort(36, (short) packet.directionHandleState().protocolCode());
        buffer.putShort(38, (short) packet.masterHandleState().protocolCode());
        buffer.putShort(40, (short) clampPercent(packet.tractionNotchPercent()));
        buffer.putShort(42, (short) clampPercent(packet.brakeNotchPercent()));
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

    private void validateIdentify(byte[] payload, int expectedIdentify, String label) {
        if ((payload[0] & 0xFF) != expectedIdentify)
            throw new IllegalArgumentException(label + " has missing or invalid identify header");
    }

    private void requireMinLength(byte[] payload, int min, String label) {
        if (payload == null || payload.length < min)
            throw new IllegalArgumentException(label + " packet too short: expected " + min + " bytes");
    }

    private void requireAllowedCode(String field, int actual, int... allowed) {
        for (int v : allowed) { if (actual == v) return; }
        throw new IllegalArgumentException(field + " has unsupported code: " + actual);
    }

    private void requirePercent(String field, int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be in 0..100: " + value);
    }
}
