package com.railwaysim.vehicle.external;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExternalUdpPacketCodec {

    private static final int COMMAND_FIELDS_PER_TRAIN = 2;
    private static final int TELEMETRY_FIELDS_PER_TRAIN = 3;

    public byte[] encodeCommands(List<ExternalTrainCommand> commands, int maxTrains) {
        int trainCount = boundedTrainCount(maxTrains);
        Map<Integer, ExternalTrainCommand> commandByTrain = commands.stream()
            .collect(Collectors.toMap(ExternalTrainCommand::trainNo, command -> command, (left, right) -> right));
        ByteBuffer buffer = ByteBuffer
            .allocate(commandPacketBytes(trainCount))
            .order(ByteOrder.LITTLE_ENDIAN);

        for (int trainNo = 1; trainNo <= trainCount; trainNo++) {
            ExternalTrainCommand command = commandByTrain.get(trainNo);
            buffer.putDouble(command == null ? 0 : command.command());
            buffer.putDouble(command == null ? 0 : command.percent());
        }
        return buffer.array();
    }

    public List<ExternalTrainTelemetry> decodeTelemetry(byte[] payload, int maxTrains) {
        int trainCount = boundedTrainCount(maxTrains);
        int expectedBytes = telemetryPacketBytes(trainCount);
        if (payload.length < expectedBytes) {
            throw new IllegalArgumentException(
                "UDP telemetry packet too short: expected " + expectedBytes + " bytes, got " + payload.length
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        return java.util.stream.IntStream.rangeClosed(1, trainCount)
            .mapToObj(trainNo -> new ExternalTrainTelemetry(
                trainNo,
                1,
                ExternalTrainDirection.UNKNOWN,
                buffer.getDouble(),
                buffer.getDouble(),
                buffer.getDouble()
            ))
            .sorted(Comparator.comparingInt(ExternalTrainTelemetry::trainNo))
            .toList();
    }

    public int commandPacketBytes(int maxTrains) {
        return boundedTrainCount(maxTrains) * COMMAND_FIELDS_PER_TRAIN * Double.BYTES;
    }

    public int telemetryPacketBytes(int maxTrains) {
        return boundedTrainCount(maxTrains) * TELEMETRY_FIELDS_PER_TRAIN * Double.BYTES;
    }

    private int boundedTrainCount(int maxTrains) {
        return Math.max(1, Math.min(20, maxTrains));
    }
}
