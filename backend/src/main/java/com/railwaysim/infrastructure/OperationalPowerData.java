package com.railwaysim.infrastructure;

import java.util.Comparator;
import java.util.List;

public record OperationalPowerData(
    double nominalVoltage,
    double minimumVoltage,
    double cutoffVoltage,
    double maxTractionCurrentAmps,
    double currentToVoltageDrop,
    List<PowerSectionDefinition> sections
) {
    public OperationalPowerData {
        sections = sections.stream()
            .sorted(Comparator.comparingDouble(PowerSectionDefinition::startMeters))
            .toList();
    }

    public record PowerSectionDefinition(
        String id,
        String name,
        double startMeters,
        double endMeters,
        double substationVoltage,
        boolean energized
    ) {
    }
}
