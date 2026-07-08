package com.railwaysim.signal.vehicle;

import com.railwaysim.train.TrainState;

public record VehicleSignalStatus(
    String trainId,
    double headMileage,
    double tailMileage,
    double speedMetersPerSecond,
    boolean zeroSpeed,
    String doorState,
    boolean tractionAvailable,
    boolean brakeAvailable,
    String currentCollectionStatus,
    double loadMassKg,
    String overloadStatus,
    int availableTractionCount,
    int availableBrakeCount,
    String vehicleProtectionReason,
    String dataQuality
) {
    public static VehicleSignalStatus from(TrainState train) {
        return new VehicleSignalStatus(
            train.id(),
            train.headMileage(),
            train.tailMileage(),
            train.speedMetersPerSecond(),
            train.zeroSpeed(),
            train.doorState(),
            train.tractionAvailable(),
            train.brakeAvailable(),
            train.currentCollectionStatus(),
            train.loadMassKg(),
            train.overloadStatus(),
            train.availableTractionCount(),
            train.availableBrakeCount(),
            train.vehicleProtectionReason(),
            train.dataQuality()
        );
    }
}
