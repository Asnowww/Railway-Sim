package com.railwaysim.vehicleruntime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PowerConstraintSnapshot(
    String trainId,
    String sectionId,
    double railVoltage,
    double powerAvailableWatts,
    Double regenPowerAvailableWatts,
    boolean energized,
    double powerDeratingFactor,
    boolean currentCollectionAvailable,
    boolean regenAvailable,
    String constraintReason
) {
    public PowerConstraintSnapshot(
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
        this(
            trainId,
            sectionId,
            railVoltage,
            powerAvailableWatts,
            null,
            energized,
            powerDeratingFactor,
            currentCollectionAvailable,
            regenAvailable,
            constraintReason
        );
    }
}
