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

    public static final int NETWORK_SCREEN_BYTES = 572;
    public static final int SIGNAL_SCREEN_BYTES = 66;
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
        writeCommonHeader(buffer, NETWORK_SCREEN_BYTES, 0x1001);
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
        buffer.putShort(568, boundedShort(train.faultLevel()));
        buffer.putShort(570, boundedShort(trainNo(train.id())));
        return buffer.array();
    }

    public byte[] encodeSignalScreen(TrainState train, SignalCabDisplayState display) {
        ByteBuffer buffer = ByteBuffer.allocate(SIGNAL_SCREEN_BYTES).order(byteOrder);
        writeCommonHeader(buffer, SIGNAL_SCREEN_BYTES, 0x1002);
        writeTimeFields(buffer, 24);
        buffer.put(36, stationCode(train.currentStationId()));
        buffer.put(37, (byte) 0);
        buffer.put(38, (byte) 0);
        buffer.put(39, (byte) 1);
        buffer.put(40, (byte) (display.doorControlMode() == SignalCabDisplayState.DoorControlMode.MANUAL ? 1 : 0));
        buffer.put(41, (byte) (display.currentDrivingMode() == SignalCabDisplayState.DrivingMode.ATO ? 1 : 0));
        buffer.put(42, direction(train.direction()));
        buffer.put(43, (byte) 0);
        buffer.putFloat(44, (float) train.speedMetersPerSecond());
        buffer.putFloat(48, (float) train.accelerationMetersPerSecondSquared());
        buffer.putShort(52, boundedShort(display.speedLimitMetersPerSecond()));
        buffer.put(54, runMode(display));
        buffer.put(55, train.tractionAvailable() ? (byte) 0 : (byte) 3);
        buffer.put(56, train.brakeAvailable() ? (byte) 1 : (byte) 2);
        buffer.put(57, display.emergencyBrake() ? (byte) 1 : (byte) 0);
        buffer.put(58, (byte) 0);
        buffer.put(59, signalState(display));
        buffer.putShort(60, boundedShort(trainNo(train.id())));
        buffer.putFloat(62, (float) display.distanceToNextStationMeters());
        return buffer.array();
    }

    public boolean isNetworkScreenPacket(byte[] payload) {
        return hasIdentify(payload) && payload.length >= NETWORK_SCREEN_BYTES;
    }

    public boolean isSignalScreenPacket(byte[] payload) {
        return hasIdentify(payload) && payload.length >= SIGNAL_SCREEN_BYTES;
    }

    private void writeCommonHeader(ByteBuffer buffer, int totalLength, int msgId) {
        buffer.put(IDENTIFY);
        buffer.putShort((short) totalLength);
        buffer.putShort((short) (totalLength - HEADER_BYTES));
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
