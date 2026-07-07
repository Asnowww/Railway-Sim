package com.railwaysim.power;

public record PowerSectionState(
    String id,
    String name,
    double startMeters,
    double endMeters,
    double voltage,
    double current,
    String status
) {
}

