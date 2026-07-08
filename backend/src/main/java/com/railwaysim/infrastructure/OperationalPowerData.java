package com.railwaysim.infrastructure;

import java.util.Comparator;
import java.util.List;

public record OperationalPowerData(
    double nominalVoltage,
    double minimumVoltage,
    double cutoffVoltage,
    double maxTractionCurrentAmps,
    double overCurrentThresholdAmps,
    double currentToVoltageDrop,
    boolean sameSectionAbsorbFirst,
    String unabsorbedRegenMode,
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
        String substationId,
        String feederId,
        double startMeters,
        double endMeters,
        double substationVoltage,
        boolean energized,
        String breakerStatus,
        String isolatorStatus,
        String supplyMode,
        String maintenanceState,
        String lockoutState,
        double resistanceOhmPerMeter
    ) {
    }
}
