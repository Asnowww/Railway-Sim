package com.railwaysim.power;

public record PowerConstraint(
    String trainId,
    String sectionId,
    double railVoltage,
    double powerAvailableWatts,
    boolean energized
) {
}
