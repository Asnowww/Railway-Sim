package com.railwaysim.power;

public record PowerConstraint(
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
    public PowerConstraint(
        String trainId,
        String sectionId,
        double railVoltage,
        double powerAvailableWatts,
        boolean energized
    ) {
        this(
            trainId,
            sectionId,
            railVoltage,
            powerAvailableWatts,
            energized,
            energized ? 1.0 : 0.0,
            energized,
            energized,
            energized ? "NORMAL" : "POWER_UNAVAILABLE"
        );
    }
}
