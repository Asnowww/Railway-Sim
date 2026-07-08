package com.railwaysim.vehicle.external;

import com.railwaysim.vehicle.VehiclePhysicsInput;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExternalVehicleCommandMapper {

    private static final Pattern TRAIN_NO_PATTERN = Pattern.compile("(\\d+)(?!.*\\d)");

    private final ExternalSegmentMapper segmentMapper;
    private final int maxTrains;

    public ExternalVehicleCommandMapper(ExternalSegmentMapper segmentMapper, int maxTrains) {
        this.segmentMapper = segmentMapper;
        this.maxTrains = Math.max(1, Math.min(20, maxTrains));
    }

    public List<ExternalTrainCommand> toCommands(List<VehiclePhysicsInput> inputs) {
        return inputs.stream().map(this::toCommand).toList();
    }

    public ExternalTrainCommand toCommand(VehiclePhysicsInput input) {
        int trainNo = resolveTrainNo(input.trainId(), maxTrains);
        CommandAndPercent commandAndPercent = commandAndPercent(input);
        ExternalSegmentPosition segmentPosition = segmentMapper.mapPosition(input.positionMeters());
        return new ExternalTrainCommand(
            trainNo,
            commandAndPercent.command(),
            commandAndPercent.percent(),
            segmentPosition.segNo(),
            segmentPosition.offset(),
            ExternalTrainDirection.UNKNOWN,
            1
        );
    }

    public static int resolveTrainNo(String trainId, int maxTrains) {
        Matcher matcher = TRAIN_NO_PATTERN.matcher(trainId == null ? "" : trainId);
        if (!matcher.find()) {
            throw new IllegalArgumentException("External simulator train id must have a numeric suffix: " + trainId);
        }
        int trainNo = Integer.parseInt(matcher.group(1));
        int boundedMax = Math.max(1, Math.min(20, maxTrains));
        if (trainNo < 1 || trainNo > boundedMax) {
            throw new IllegalArgumentException("External simulator train no out of range 1.." + boundedMax + ": " + trainNo);
        }
        return trainNo;
    }

    private CommandAndPercent commandAndPercent(VehiclePhysicsInput input) {
        if (input.emergencyBrakeCommand()) {
            return new CommandAndPercent(2, 100);
        }
        if (input.brakeCommand() > 0) {
            return new CommandAndPercent(2, roundedPercent(input.brakeCommand()));
        }
        if (input.tractionCommand() > 0) {
            return new CommandAndPercent(1, roundedPercent(input.tractionCommand()));
        }
        return new CommandAndPercent(0, 0);
    }

    private double roundedPercent(double commandRatio) {
        return Math.round(clamp(commandRatio, 0, 1) * 100);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CommandAndPercent(int command, double percent) {
    }
}
