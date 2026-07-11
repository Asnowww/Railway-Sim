package com.railwaysim.vehicleruntime.model;

import java.util.Map;

public record VehicleParameterMetadata(
    String parameterSchemaVersion,
    String parameterSetId,
    String curveSetId,
    String sourcePath,
    String trainType,
    double lengthMeters,
    String formation,
    int motorCount,
    int axleCount,
    double wheelRadiusMeters,
    double gearRatio,
    double emptyMassKg,
    double maxLoadMassKg,
    Map<String, Double> loadCasesKg,
    double hardMassLimitKg,
    int curvePointCount,
    double maxMechanicalTractionPowerWatts,
    double tractionTotalEfficiency,
    double regenTotalEfficiency,
    double referenceVoltageVolts
) {
}
