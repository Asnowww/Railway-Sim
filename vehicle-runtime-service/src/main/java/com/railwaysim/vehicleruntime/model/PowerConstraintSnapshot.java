package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PowerConstraintSnapshot(
    String trainId,
    String sectionId,
    double railVoltage,
    double powerAvailableWatts,
    boolean energized,
    double powerDeratingFactor,
    boolean currentCollectionAvailable,
    boolean regenAvailable,
    String constraintReason
) {
}
