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
    List<PowerSectionDefinition> sections,
    List<TractionSubstationDefinition> substations,
    List<ThirdRailSectionDefinition> thirdRailSections,
    List<IsolatorSwitchDefinition> isolators,
    List<ReturnCurrentDeviceDefinition> returnCurrentDevices,
    List<StrayCurrentMonitorPointDefinition> strayCurrentMonitorPoints
) {
    public OperationalPowerData {
        sections = sections.stream()
            .sorted(Comparator.comparingDouble(PowerSectionDefinition::startMeters))
            .toList();
        substations = List.copyOf(substations);
        thirdRailSections = thirdRailSections.stream()
            .sorted(Comparator.comparingDouble(ThirdRailSectionDefinition::startMeters))
            .toList();
        isolators = List.copyOf(isolators);
        returnCurrentDevices = List.copyOf(returnCurrentDevices);
        strayCurrentMonitorPoints = strayCurrentMonitorPoints.stream()
            .sorted(Comparator.comparingDouble(StrayCurrentMonitorPointDefinition::positionMeters))
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

    public record TractionSubstationDefinition(
        String id,
        String name,
        double startMeters,
        double endMeters,
        String supplyMode,
        boolean available,
        List<String> feederIds,
        List<String> sectionIds,
        List<SubstationDeviceDefinition> devices
    ) {
        public TractionSubstationDefinition {
            supplyMode = supplyMode == null || supplyMode.isBlank() ? "DOUBLE_END" : supplyMode;
            feederIds = List.copyOf(feederIds);
            sectionIds = List.copyOf(sectionIds);
            devices = List.copyOf(devices);
        }
    }

    public record SubstationDeviceDefinition(
        String id,
        String name,
        String deviceType,
        String defaultState,
        double ratedVoltage,
        double ratedCurrentAmps,
        double locationMeters,
        List<String> affectsSectionIds
    ) {
        public SubstationDeviceDefinition {
            deviceType = deviceType == null || deviceType.isBlank() ? "GENERIC" : deviceType;
            defaultState = defaultState == null || defaultState.isBlank() ? "AVAILABLE" : defaultState;
            affectsSectionIds = List.copyOf(affectsSectionIds);
        }
    }

    public record ThirdRailSectionDefinition(
        String id,
        String name,
        String powerSectionId,
        double startMeters,
        double endMeters,
        List<String> isolatorIds,
        String returnCurrentDeviceId
    ) {
        public ThirdRailSectionDefinition {
            powerSectionId = powerSectionId == null || powerSectionId.isBlank() ? id : powerSectionId;
            isolatorIds = List.copyOf(isolatorIds);
            returnCurrentDeviceId = returnCurrentDeviceId == null ? "" : returnCurrentDeviceId;
        }
    }

    public record IsolatorSwitchDefinition(
        String id,
        String name,
        String thirdRailSectionId,
        double positionMeters,
        String defaultState,
        boolean normallyOpen
    ) {
        public IsolatorSwitchDefinition {
            defaultState = defaultState == null || defaultState.isBlank() ? "CLOSED" : defaultState;
        }
    }

    public record ReturnCurrentDeviceDefinition(
        String id,
        String name,
        String sectionId,
        String deviceType,
        String defaultState
    ) {
        public ReturnCurrentDeviceDefinition {
            deviceType = deviceType == null || deviceType.isBlank() ? "RETURN_CURRENT" : deviceType;
            defaultState = defaultState == null || defaultState.isBlank() ? "NORMAL" : defaultState;
        }
    }

    public record StrayCurrentMonitorPointDefinition(
        String id,
        String name,
        String sectionId,
        double positionMeters,
        String returnCurrentDeviceId,
        double normalMinPotentialVolts,
        double normalMaxPotentialVolts
    ) {
        public StrayCurrentMonitorPointDefinition {
            returnCurrentDeviceId = returnCurrentDeviceId == null ? "" : returnCurrentDeviceId;
        }
    }
}
