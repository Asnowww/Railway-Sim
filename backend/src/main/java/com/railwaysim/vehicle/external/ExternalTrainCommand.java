package com.railwaysim.vehicle.external;

public record ExternalTrainCommand(
    int trainNo,
    int command,
    double percent,
    int segNo,
    double offset,
    ExternalTrainDirection direction,
    int activeCab
) {
    public ExternalTrainCommand {
        if (trainNo < 1 || trainNo > 20) {
            throw new IllegalArgumentException("trainNo must be in 1..20");
        }
        if (command < 0 || command > 2) {
            throw new IllegalArgumentException("command must be 0(coast), 1(accelerate), or 2(decelerate)");
        }
        percent = clamp(percent, 0, 100);
        segNo = Math.max(1, segNo);
        offset = Math.max(0, offset);
        direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
        activeCab = Math.max(1, activeCab);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
