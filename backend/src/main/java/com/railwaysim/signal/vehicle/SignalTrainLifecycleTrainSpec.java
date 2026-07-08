package com.railwaysim.signal.vehicle;

import com.railwaysim.vehicle.external.ExternalTrainDirection;

public record SignalTrainLifecycleTrainSpec(
    int trainNo,
    int linkId,
    double offsetMeters,
    ExternalTrainDirection direction
) {
    public SignalTrainLifecycleTrainSpec {
        if (trainNo < 1 || trainNo > 65_535) {
            throw new IllegalArgumentException("trainNo must be in 1..65535");
        }
        linkId = Math.max(0, Math.min(65_535, linkId));
        offsetMeters = Math.max(0, offsetMeters);
        direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
    }

    public static SignalTrainLifecycleTrainSpec add(
        int trainNo,
        int linkId,
        double offsetMeters,
        ExternalTrainDirection direction
    ) {
        return new SignalTrainLifecycleTrainSpec(trainNo, linkId, offsetMeters, direction);
    }

    public static SignalTrainLifecycleTrainSpec delete(int trainNo) {
        return new SignalTrainLifecycleTrainSpec(trainNo, 0, 0, ExternalTrainDirection.UNKNOWN);
    }

    public String trainId() {
        return "TR-%03d".formatted(trainNo);
    }
}
