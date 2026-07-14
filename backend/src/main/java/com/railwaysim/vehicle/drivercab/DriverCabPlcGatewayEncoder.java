package com.railwaysim.vehicle.drivercab;

import com.railwaysim.api.dto.DriverCabPlcGatewayRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** Protocol-only gateway encoder. It does not execute or retain vehicle control state in 8080. */
@Component
public class DriverCabPlcGatewayEncoder {
    public static final int PLC_INPUT_BYTES = 46;

    /** Project a structured browser request to the display snapshot the front-end console reads back. */
    public DriverCabStateSnapshot toSnapshot(DriverCabPlcGatewayRequest input, DriverCabControlSource source) {
        if (input == null) throw new IllegalArgumentException("driver cab PLC input is required");
        return new DriverCabStateSnapshot(
            input.doorModeSwitchState(), input.atoModeActive(), input.atoStartFlag(),
            input.modeUpgradeConfirmFlag(), input.modeDowngradeConfirmFlag(),
            input.automaticTurnbackFlag(), input.directionHandleState(), input.masterHandleState(),
            input.keySwitchLocked(), input.tractionNotchPercent(), input.brakeNotchPercent(),
            source, Instant.now()
        );
    }

    /** Decode a raw 46-byte PLC frame (as sent by the physical console) back to a display snapshot. */
    public DriverCabStateSnapshot decodeSnapshot(byte[] payload, DriverCabControlSource source) {
        if (payload == null || payload.length != PLC_INPUT_BYTES) {
            throw new IllegalArgumentException("driver cab PLC input must be exactly 46 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int traction = clampPercent(Short.toUnsignedInt(buffer.getShort(40)));
        int brake = clampPercent(Short.toUnsignedInt(buffer.getShort(42)));
        return new DriverCabStateSnapshot(
            DriverCabDoorModeSwitch.fromProtocolCode(Short.toUnsignedInt(buffer.getShort(32))),
            bit(payload, 25, 2),
            bit(payload, 34, 7), bit(payload, 34, 2), bit(payload, 34, 3), bit(payload, 34, 5),
            DriverCabDirectionHandleState.fromProtocolCode(Short.toUnsignedInt(buffer.getShort(36))),
            DriverCabMasterHandleState.fromProtocolCode(Short.toUnsignedInt(buffer.getShort(38))),
            bit(payload, 35, 1), traction, brake, source, Instant.now()
        );
    }

    private boolean bit(byte[] payload, int byteOffset, int bitOffset) {
        return (payload[byteOffset] & (1 << bitOffset)) != 0;
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public byte[] encode(DriverCabPlcGatewayRequest input) {
        if (input == null) throw new IllegalArgumentException("driver cab PLC input is required");
        ByteBuffer buffer = ByteBuffer.allocate(PLC_INPUT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        writeHeader(buffer);
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

    private void writeHeader(ByteBuffer buffer) {
        buffer.put(new byte[] {0x55, (byte) 0xaa, 0x55, (byte) 0xaa});
        buffer.putShort((short) PLC_INPUT_BYTES);
        buffer.putShort((short) (PLC_INPUT_BYTES - 24));
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

    private void setBit(byte[] payload, int byteOffset, int bitOffset, boolean enabled) {
        int value = Byte.toUnsignedInt(payload[byteOffset]);
        payload[byteOffset] = (byte) (enabled ? value | (1 << bitOffset) : value & ~(1 << bitOffset));
    }
}
