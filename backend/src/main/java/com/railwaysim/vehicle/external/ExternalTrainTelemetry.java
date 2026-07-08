package com.railwaysim.vehicle.external;

public record ExternalTrainTelemetry(
    int trainNo,
    int activeCab,
    ExternalTrainDirection direction,
    double acceleration,
    double speed,
    double cumulativeDistance
) {
    public ExternalTrainTelemetry {
        if (trainNo < 1 || trainNo > 20) {
            throw new IllegalArgumentException("trainNo must be in 1..20");
        }
        activeCab = Math.max(1, activeCab);
        direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
    }
}
