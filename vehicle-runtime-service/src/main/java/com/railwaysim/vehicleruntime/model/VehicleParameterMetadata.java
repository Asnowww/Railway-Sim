package com.railwaysim.vehicleruntime.model;

public record VehicleParameterMetadata(
    String parameterSetId,
    String sourcePath,
    String trainType,
    double emptyMassKg,
    double maxLoadMassKg,
    double maxMechanicalTractionPowerWatts
) {
}
