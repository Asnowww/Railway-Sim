package com.railwaysim.localnet.drivercab;

import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.vehicle.SignalCabDisplayState;
import com.railwaysim.train.TrainState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public class DriverCabScreenPacketCodec {

    /** Real HMI TCP payload. The legacy document incorrectly included a trailing faultCode WORD. */
    public static final int NETWORK_SCREEN_BYTES = 570;
    public static final int NETWORK_SCREEN_INPUT_BYTES = 26;
    /** Real MMI TCP payload. Its historical header still reports totalLen=62. */
    public static final int SIGNAL_SCREEN_BYTES = 68;
    public static final int SIGNAL_SCREEN_HEADER_TOTAL_BYTES = 62;
    public static final int SIGNAL_SCREEN_HEADER_DATA_BYTES = 42;
    private static final byte[] IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};
    private static final int HEADER_BYTES = 24;

    private final ByteOrder byteOrder;

    public DriverCabScreenPacketCodec() {
        this(ByteOrder.LITTLE_ENDIAN);
    }

    public DriverCabScreenPacketCodec(ByteOrder byteOrder) {
        this.byteOrder = byteOrder == null ? ByteOrder.LITTLE_ENDIAN : byteOrder;
    }

    public byte[] encodeNetworkScreen(
        TrainState train,
        SignalCabDisplayState display,
        List<PowerSectionState> powerSections
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(NETWORK_SCREEN_BYTES).order(byteOrder);
        writeCommonHeader(buffer, NETWORK_SCREEN_BYTES, NETWORK_SCREEN_BYTES - HEADER_BYTES, 0x1001);
        writeTimeFields(buffer, 24);
        buffer.put(36, stationCode(train.currentStationId()));
        buffer.put(37, (byte) 0);
        buffer.put(38, (byte) 0);
        buffer.put(39, (byte) (powerAvailable(train) ? 1 : 0));
        buffer.putFloat(40, (float) train.speedMetersPerSecond());
        buffer.putFloat(44, (float) train.accelerationMetersPerSecondSquared());
        buffer.putShort(48, boundedShort(Math.abs(train.tractionForceNewtons())));
        buffer.putShort(50, boundedShort(averageVoltage(powerSections)));
        buffer.putShort(52, boundedShort(display.speedLimitMetersPerSecond()));
        buffer.put(54, levelPosition(train, display));
        buffer.put(55, runMode(display));
        buffer.putShort(56, boundedShort(averageVoltage(powerSections)));
        buffer.put(58, direction(train.direction()));
        buffer.put(59, (byte) 0x01);
        buffer.put(108, nibbleByCar(train.tractionAvailable() ? 0 : 3));
        buffer.putShort(132, boundedShort(Math.max(train.tractionForceNewtons(), train.brakeForceNewtons())));
        buffer.putShort(156, boundedShort(train.brakeForceNewtons() / 1000.0));
        buffer.put(168, boundedByte(train.loadRate() * 100));
        buffer.put(174, boundedByte(Math.abs(train.railCurrentAmps())));
        buffer.putShort(244, boundedShort(averageVoltage(powerSections)));
        buffer.putShort(568, boundedShort(trainNo(train.id())));
        return buffer.array();
    }

    public byte[] encodeSignalScreen(TrainState train, SignalCabDisplayState display) {
        ByteBuffer buffer = ByteBuffer.allocate(SIGNAL_SCREEN_BYTES).order(byteOrder);
        writeCommonHeader(
            buffer,
            SIGNAL_SCREEN_HEADER_TOTAL_BYTES,
            SIGNAL_SCREEN_HEADER_DATA_BYTES,
            0x1002
        );
        writeTimeFields(buffer, 24);
        buffer.put(36, stationCode(train.currentStationId()));
        buffer.put(37, (byte) 0);
        buffer.put(38, (byte) 0);
        buffer.put(39, (byte) 1);
        buffer.put(40, (byte) (display.doorControlMode() == SignalCabDisplayState.DoorControlMode.MANUAL ? 1 : 0));
        buffer.put(41, (byte) (display.currentDrivingMode() == SignalCabDisplayState.DrivingMode.ATO ? 1 : 0));
        buffer.put(42, signalScreenDirection(train.direction()));
        buffer.put(43, (byte) 0);
        buffer.putFloat(44, (float) (train.speedMetersPerSecond() * 3.6));
        buffer.putFloat(48, (float) train.accelerationMetersPerSecondSquared());
        buffer.putShort(52, (short) 0);
        buffer.putShort(54, boundedShort(display.speedLimitMetersPerSecond() * 3.6));
        buffer.put(56, runMode(display));
        buffer.put(57, train.tractionAvailable() ? (byte) 0 : (byte) 3);
        buffer.put(58, train.brakeAvailable() ? (byte) 1 : (byte) 2);
        buffer.put(59, display.emergencyBrake() ? (byte) 1 : (byte) 0);
        buffer.put(60, (byte) 0);
        buffer.put(61, signalState(display));
        buffer.putShort(62, boundedShort(trainNo(train.id())));
        buffer.putFloat(64, (float) display.distanceToNextStationMeters());
        return buffer.array();
    }

    public boolean isNetworkScreenPacket(byte[] payload) {
        return hasIdentify(payload) && payload.length >= NETWORK_SCREEN_BYTES;
    }

    public int decodeTractionCutMask(byte[] payload) {
        validateCommonHeader(payload, NETWORK_SCREEN_INPUT_BYTES, 2, "network screen input");
        return Byte.toUnsignedInt(payload[24]) & 0x3f;
    }

    public NetworkScreenFrame decodeNetworkScreen(byte[] payload) {
        validateCommonHeader(payload, NETWORK_SCREEN_BYTES, NETWORK_SCREEN_BYTES - HEADER_BYTES,
            "network screen output");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        return new NetworkScreenFrame(
            Short.toUnsignedInt(buffer.getShort(4)), Short.toUnsignedInt(buffer.getShort(6)),
            Short.toUnsignedInt(buffer.getShort(22)),
            Short.toUnsignedInt(buffer.getShort(24)), Short.toUnsignedInt(buffer.getShort(26)),
            Short.toUnsignedInt(buffer.getShort(28)), Short.toUnsignedInt(buffer.getShort(30)),
            Short.toUnsignedInt(buffer.getShort(32)), Short.toUnsignedInt(buffer.getShort(34)),
            Byte.toUnsignedInt(buffer.get(36)), Byte.toUnsignedInt(buffer.get(37)),
            Byte.toUnsignedInt(buffer.get(38)), Byte.toUnsignedInt(buffer.get(39)),
            buffer.getFloat(40), buffer.getFloat(44), Short.toUnsignedInt(buffer.getShort(48)),
            Short.toUnsignedInt(buffer.getShort(50)), Short.toUnsignedInt(buffer.getShort(52)),
            Byte.toUnsignedInt(buffer.get(54)), Byte.toUnsignedInt(buffer.get(55)),
            Short.toUnsignedInt(buffer.getShort(56)), Byte.toUnsignedInt(buffer.get(58)),
            Byte.toUnsignedInt(buffer.get(59)), Short.toUnsignedInt(buffer.getShort(568)), payload.clone()
        );
    }

    public SignalScreenFrame decodeSignalScreen(byte[] payload) {
        validateWireAndHeader(
            payload, SIGNAL_SCREEN_BYTES, SIGNAL_SCREEN_HEADER_TOTAL_BYTES,
            SIGNAL_SCREEN_HEADER_DATA_BYTES, "signal screen output"
        );
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        return new SignalScreenFrame(
            Short.toUnsignedInt(buffer.getShort(4)), Short.toUnsignedInt(buffer.getShort(6)),
            Short.toUnsignedInt(buffer.getShort(22)),
            Short.toUnsignedInt(buffer.getShort(24)), Short.toUnsignedInt(buffer.getShort(26)),
            Short.toUnsignedInt(buffer.getShort(28)), Short.toUnsignedInt(buffer.getShort(30)),
            Short.toUnsignedInt(buffer.getShort(32)), Short.toUnsignedInt(buffer.getShort(34)),
            Byte.toUnsignedInt(buffer.get(36)), Byte.toUnsignedInt(buffer.get(37)),
            Byte.toUnsignedInt(buffer.get(38)), Byte.toUnsignedInt(buffer.get(39)),
            Byte.toUnsignedInt(buffer.get(40)), Byte.toUnsignedInt(buffer.get(41)),
            Byte.toUnsignedInt(buffer.get(42)), Byte.toUnsignedInt(buffer.get(43)),
            buffer.getFloat(44), buffer.getFloat(48), Short.toUnsignedInt(buffer.getShort(52)),
            Short.toUnsignedInt(buffer.getShort(54)), Byte.toUnsignedInt(buffer.get(56)),
            Byte.toUnsignedInt(buffer.get(57)), Byte.toUnsignedInt(buffer.get(58)),
            Byte.toUnsignedInt(buffer.get(59)), Byte.toUnsignedInt(buffer.get(60)),
            Byte.toUnsignedInt(buffer.get(61)), Short.toUnsignedInt(buffer.getShort(62)),
            buffer.getFloat(64), payload.clone()
        );
    }

    public boolean isSignalScreenPacket(byte[] payload) {
        return hasIdentify(payload) && payload.length >= SIGNAL_SCREEN_BYTES;
    }

    private void writeCommonHeader(ByteBuffer buffer, int headerTotalLength, int dataLength, int msgId) {
        buffer.put(IDENTIFY);
        buffer.putShort((short) headerTotalLength);
        buffer.putShort((short) dataLength);
        buffer.putLong(System.currentTimeMillis());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) msgId);
    }

    private void writeTimeFields(ByteBuffer buffer, int offset) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        buffer.putShort(offset, (short) now.getYear());
        buffer.putShort(offset + 2, (short) now.getMonthValue());
        buffer.putShort(offset + 4, (short) now.getDayOfMonth());
        buffer.putShort(offset + 6, (short) now.getHour());
        buffer.putShort(offset + 8, (short) now.getMinute());
        buffer.putShort(offset + 10, (short) now.getSecond());
    }

    private boolean hasIdentify(byte[] payload) {
        if (payload == null || payload.length < IDENTIFY.length) {
            return false;
        }
        for (int i = 0; i < IDENTIFY.length; i++) {
            if (payload[i] != IDENTIFY[i]) {
                return false;
            }
        }
        return true;
    }

    private void validateCommonHeader(byte[] payload, int totalLength, int dataLength, String label) {
        if (payload == null || payload.length != totalLength || !hasIdentify(payload)) {
            throw new IllegalArgumentException(label + " identify or total frame length is invalid");
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(byteOrder);
        if (Short.toUnsignedInt(header.getShort(4)) != totalLength) {
            throw new IllegalArgumentException(label + " total length is invalid");
        }
        if (Short.toUnsignedInt(header.getShort(6)) != dataLength) {
            throw new IllegalArgumentException(label + " data length is invalid");
        }
    }

    private short boundedShort(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return (short) Math.max(0, Math.min(0xffff, Math.round(value)));
    }

    private byte boundedByte(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return (byte) Math.max(0, Math.min(0xff, Math.round(value)));
    }

    private byte levelPosition(TrainState train, SignalCabDisplayState display) {
        if (display.emergencyBrake()) {
            return 3;
        }
        if (train.brakeForceNewtons() > 0) {
            return 2;
        }
        if (train.tractionForceNewtons() > 0) {
            return 1;
        }
        return 0;
    }

    private byte runMode(SignalCabDisplayState display) {
        int low = switch (display.currentDrivingMode()) {
            case DTO, ATO, AR -> 1;
            case SM, RM -> 0;
        };
        int high = switch (display.doorControlMode()) {
            case AUTOMATIC -> 2;
            case SEMI_AUTOMATIC -> 1;
            case MANUAL -> 0;
        };
        return (byte) ((high << 4) | low);
    }

    private byte direction(String direction) {
        if ("UP".equalsIgnoreCase(direction)) {
            return 1;
        }
        if ("DOWN".equalsIgnoreCase(direction)) {
            return 2;
        }
        return (byte) 0xff;
    }

    private void validateWireAndHeader(
        byte[] payload,
        int wireLength,
        int headerTotalLength,
        int dataLength,
        String label
    ) {
        if (payload == null || payload.length != wireLength || !hasIdentify(payload)) {
            throw new IllegalArgumentException(label + " identify or wire frame length is invalid");
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(byteOrder);
        if (Short.toUnsignedInt(header.getShort(4)) != headerTotalLength
            || Short.toUnsignedInt(header.getShort(6)) != dataLength) {
            throw new IllegalArgumentException(label + " header length fields are invalid");
        }
    }

    public record NetworkScreenFrame(
        int headerTotalLength, int dataLength, int messageId,
        int year, int month, int day, int hour, int minute, int second,
        int currentStation, int nextStation, int terminalStation, int controlState,
        float speedMetersPerSecond, float accelerationMetersPerSecondSquared,
        int tractionForce, int lineVoltage, int speedLimit, int levelPosition, int runMode,
        int currentVoltage, int direction, int runningState, int trainNumber, byte[] rawPayload
    ) {
        public NetworkScreenFrame { rawPayload = rawPayload.clone(); }
        @Override public byte[] rawPayload() { return rawPayload.clone(); }
    }

    public record SignalScreenFrame(
        int headerTotalLength, int dataLength, int messageId,
        int year, int month, int day, int hour, int minute, int second,
        int currentStation, int nextStation, int terminalStation,
        int centralMonitorState, int manualMode, int ctcState, int direction, int reserved,
        float speedKilometersPerHour, float accelerationMetersPerSecondSquared,
        int tractionCut, int speedLimitKilometersPerHour, int mode, int tractionState,
        int brakeState, int emergencyBrake, int eventId, int signalState,
        int trainNumber, float distanceToNextStationMeters, byte[] rawPayload
    ) {
        public SignalScreenFrame { rawPayload = rawPayload.clone(); }
        @Override public byte[] rawPayload() { return rawPayload.clone(); }
    }

    private byte signalScreenDirection(String direction) {
        return "DOWN".equalsIgnoreCase(direction) ? (byte) 1 : (byte) 0;
    }

    private byte signalState(SignalCabDisplayState display) {
        int value = 0;
        if (display.departureInfo() == SignalCabDisplayState.DepartureInfo.DEPART) {
            value |= 0x01;
        }
        if (display.emergencyBrake()) {
            value |= 0x02;
        }
        if (display.turnbackInfo() != SignalCabDisplayState.TurnbackInfo.INACTIVE) {
            value |= 0x04;
        }
        return (byte) value;
    }

    private byte stationCode(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return 0;
        }
        String digits = stationId.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return 0;
        }
        try {
            return boundedByte(Integer.parseInt(digits));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int trainNo(String trainId) {
        if (trainId == null) {
            return 0;
        }
        String digits = trainId.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean powerAvailable(TrainState train) {
        return !"LOST".equals(train.currentCollectionStatus()) && "ATTACHED".equals(train.powerNetworkStatus());
    }

    private double averageVoltage(List<PowerSectionState> sections) {
        if (sections == null || sections.isEmpty()) {
            return 1500;
        }
        return sections.stream().mapToDouble(PowerSectionState::voltage).average().orElse(1500);
    }

    private byte nibbleByCar(int nibble) {
        int value = 0;
        for (int i = 0; i < 6; i++) {
            value |= (nibble & 0x0f) << ((i % 2) * 4);
        }
        return (byte) value;
    }
}
