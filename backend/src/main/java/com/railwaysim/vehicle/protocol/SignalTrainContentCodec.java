package com.railwaysim.vehicle.protocol;

import com.railwaysim.vehicle.external.ExternalTrainDirection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class SignalTrainContentCodec {

    public static final int BYTES_PER_TRAIN = 18;

    private final ByteOrder byteOrder;

    public SignalTrainContentCodec() {
        this(ByteOrder.LITTLE_ENDIAN);
    }

    public SignalTrainContentCodec(ByteOrder byteOrder) {
        this.byteOrder = byteOrder == null ? ByteOrder.LITTLE_ENDIAN : byteOrder;
    }

    public List<TrainOperationalTelemetry> decode(byte[] payload, int trainCount) {
        int boundedTrainCount = Math.max(0, trainCount);
        int expectedBytes = boundedTrainCount * BYTES_PER_TRAIN;
        if (payload.length < expectedBytes) {
            throw new IllegalArgumentException(
                "Signal train content packet too short: expected " + expectedBytes + " bytes, got " + payload.length
            );
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        List<TrainOperationalTelemetry> telemetry = new ArrayList<>();
        for (int index = 0; index < boundedTrainCount; index++) {
            int trainNo = unsignedByte(buffer.get());
            long speedCmPerSecond = unsignedInt(buffer.getInt());
            long cumulativeDistanceCm = unsignedInt(buffer.getInt());
            int directionCode = unsignedByte(buffer.get());
            long loadMassKg = unsignedInt(buffer.getInt());
            int faultSpeedLimitCmPerSecond = unsignedByte(buffer.get());
            boolean emergencyBrakeApplied = unsignedByte(buffer.get()) == 1;
            int availableTractionCount = unsignedByte(buffer.get());
            int availableBrakeCount = unsignedByte(buffer.get());
            telemetry.add(new TrainOperationalTelemetry(
                trainNo,
                speedCmPerSecond / 100.0,
                cumulativeDistanceCm / 100.0,
                ExternalTrainDirection.fromProtocolCode(directionCode),
                loadMassKg,
                faultSpeedLimitCmPerSecond / 100.0,
                emergencyBrakeApplied,
                availableTractionCount,
                availableBrakeCount
            ));
        }
        return telemetry;
    }

    public byte[] encode(List<TrainOperationalTelemetry> telemetries) {
        ByteBuffer buffer = ByteBuffer
            .allocate(telemetries.size() * BYTES_PER_TRAIN)
            .order(byteOrder);
        for (TrainOperationalTelemetry telemetry : telemetries) {
            buffer.put((byte) telemetry.trainNo());
            buffer.putInt((int) Math.round(telemetry.speedMetersPerSecond() * 100));
            buffer.putInt((int) Math.round(telemetry.cumulativeDistanceMeters() * 100));
            buffer.put((byte) telemetry.direction().protocolCode());
            buffer.putInt((int) Math.round(telemetry.loadMassKg()));
            buffer.put((byte) Math.round(telemetry.faultSpeedLimitMetersPerSecond() * 100));
            buffer.put((byte) (telemetry.emergencyBrakeApplied() ? 1 : 0));
            buffer.put((byte) telemetry.availableTractionCount());
            buffer.put((byte) telemetry.availableBrakeCount());
        }
        return buffer.array();
    }

    private int unsignedByte(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private long unsignedInt(int value) {
        return Integer.toUnsignedLong(value);
    }
}
