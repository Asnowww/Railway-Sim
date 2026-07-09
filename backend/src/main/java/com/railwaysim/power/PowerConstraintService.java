package com.railwaysim.power;

import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class PowerConstraintService {

    private final StaticInfrastructureCatalog infrastructureCatalog;

    public PowerConstraintService(StaticInfrastructureCatalog infrastructureCatalog) {
        this.infrastructureCatalog = infrastructureCatalog;
    }

    public List<PowerSectionState> initializeStates(PowerNetworkStateSnapshot snapshot) {
        return calculateStates(List.of(), snapshot, Map.of(), Map.of(), Map.of());
    }

    public List<PowerSectionLoadSnapshot> loadSnapshots(List<VehiclePhysicsOutput> outputs) {
        return aggregateLoads(outputs).entrySet().stream()
            .map(entry -> new PowerSectionLoadSnapshot(
                entry.getKey(),
                entry.getValue().trainIds(),
                entry.getValue().tractionPowerWatts(),
                entry.getValue().regenPowerWatts(),
                entry.getValue().currentAmps()
            ))
            .toList();
    }

    public List<PowerSectionState> calculateStates(
        List<VehiclePhysicsOutput> outputs,
        PowerNetworkStateSnapshot snapshot,
        Map<String, String> injectedFaultBySection,
        Map<String, String> maintenanceLockBySection,
        Map<String, String> supplyModeOverrideBySection
    ) {
        OperationalPowerData powerData = infrastructureCatalog.powerData();
        Map<String, SectionLoad> loadBySection = aggregateLoads(outputs);
        Map<String, OperationalPowerData.PowerSectionDefinition> sectionDefinitions = powerData.sections().stream()
            .collect(Collectors.toMap(OperationalPowerData.PowerSectionDefinition::id, Function.identity()));
        Map<String, PowerNetworkStateSnapshot.ThirdRailSectionSnapshot> thirdRailStates = snapshot.thirdRailSections().stream()
            .collect(Collectors.toMap(PowerNetworkStateSnapshot.ThirdRailSectionSnapshot::powerSectionId, Function.identity(), (left, right) -> left));
        Map<String, PowerNetworkStateSnapshot.SubstationSnapshot> substations = snapshot.substations().stream()
            .collect(Collectors.toMap(PowerNetworkStateSnapshot.SubstationSnapshot::id, Function.identity(), (left, right) -> left));
        Map<String, List<PowerNetworkStateSnapshot.IsolatorSnapshot>> isolatorsBySection = buildIsolatorsBySection(snapshot);
        Map<String, PowerNetworkStateSnapshot.StrayCurrentSnapshot> strayBySection = snapshot.strayCurrentMonitors().stream()
            .collect(Collectors.toMap(PowerNetworkStateSnapshot.StrayCurrentSnapshot::sectionId, Function.identity(), (left, right) -> higherRisk(left, right)));
        Instant now = Instant.now();
        List<PowerSectionState> states = new ArrayList<>();
        for (OperationalPowerData.PowerSectionDefinition section : powerData.sections()) {
            SectionLoad load = loadBySection.getOrDefault(section.id(), SectionLoad.empty());
            PowerNetworkStateSnapshot.ThirdRailSectionSnapshot thirdRailState = thirdRailStates.get(section.id());
            PowerNetworkStateSnapshot.SubstationSnapshot substationState = substations.get(section.substationId());
            List<PowerNetworkStateSnapshot.IsolatorSnapshot> isolators = isolatorsBySection.getOrDefault(section.id(), List.of());
            PowerNetworkStateSnapshot.StrayCurrentSnapshot strayState = strayBySection.get(section.id());
            String supplyMode = supplyModeOverrideBySection.getOrDefault(
                section.id(),
                thirdRailState == null ? section.supplyMode() : thirdRailState.recommendedSupplyMode()
            );
            String breakerStatus = breakerStatus(section, substationState, injectedFaultBySection.get(section.id()));
            String isolatorStatus = isolatorStatus(isolators, injectedFaultBySection.get(section.id()));
            String substationAvailability = substationAvailability(substationState);
            double substationVoltage = section.substationVoltage();
            double absorbedRegenPower = powerData.sameSectionAbsorbFirst()
                ? Math.min(load.tractionPowerWatts(), load.regenPowerWatts())
                : 0;
            double unabsorbedRegenPower = Math.max(0, load.regenPowerWatts() - absorbedRegenPower);
            double absorbedCurrent = substationVoltage > 1 ? absorbedRegenPower / substationVoltage : 0;
            double netCurrent = Math.max(0, load.currentAmps() - absorbedCurrent);
            double voltageDropPerAmp = powerData.currentToVoltageDrop() + sectionResistance(section);
            double voltage = Math.max(0, substationVoltage - netCurrent * voltageDropPerAmp);
            ExternalVoltageComparison externalComparison = compareExternalVoltage(voltage, thirdRailState, snapshot);
            String lockoutState = maintenanceLockBySection.getOrDefault(section.id(), section.lockoutState());
            String maintenanceState = maintenanceState(section, maintenanceLockBySection.get(section.id()));
            String status = resolveStatus(
                section.id(),
                voltage,
                netCurrent,
                breakerStatus,
                isolatorStatus,
                maintenanceState,
                lockoutState,
                supplyMode,
                substationAvailability,
                thirdRailState,
                injectedFaultBySection.get(section.id())
            );
            double availablePower = currentCollectionAvailable(status)
                ? voltage * powerData.maxTractionCurrentAmps() * deratingFactor(status) * supplyModeFactor(supplyMode)
                : 0;
            states.add(new PowerSectionState(
                section.id(),
                section.name(),
                section.substationId(),
                section.feederId(),
                section.startMeters(),
                section.endMeters(),
                voltage,
                netCurrent,
                status,
                load.tractionPowerWatts(),
                load.regenPowerWatts(),
                absorbedRegenPower,
                unabsorbedRegenPower,
                availablePower,
                supplyMode,
                isolatorStatus,
                substationAvailability,
                breakerStatus,
                protectionState(status),
                maintenanceState,
                lockoutState,
                snapshot.dataQuality(),
                externalComparison.externalVoltage(),
                externalComparison.externalCurrent(),
                externalComparison.externalLoadWatts(),
                externalComparison.voltageDeviation(),
                externalComparison.voltageDeviationPercent(),
                externalComparison.status(),
                externalComparison.supportReason(),
                strayState == null ? "NORMAL" : strayState.riskLevel(),
                strayState == null ? "" : strayState.riskReason(),
                load.trainIds(),
                "GOOD",
                now
            ));
        }
        return states;
    }

    private ExternalVoltageComparison compareExternalVoltage(
        double centralVoltage,
        PowerNetworkStateSnapshot.ThirdRailSectionSnapshot thirdRailState,
        PowerNetworkStateSnapshot snapshot
    ) {
        boolean hasExternalVoltage = thirdRailState != null
            && thirdRailState.contactRailVoltage() > 0
            && !"LOCAL".equals(snapshot.heartbeatStatus())
            && !"FALLBACK".equals(snapshot.dataQuality());
        if (!hasExternalVoltage) {
            return new ExternalVoltageComparison(0, 0, 0, 0, 0, "NO_EXTERNAL_DATA", "");
        }

        double externalVoltage = thirdRailState.contactRailVoltage();
        double deviation = externalVoltage - centralVoltage;
        double deviationPercent = centralVoltage <= 1 ? 0 : Math.abs(deviation) / centralVoltage * 100.0;
        String status;
        if (deviationPercent <= 5.0) {
            status = "MATCHED";
        } else if (deviationPercent <= 10.0) {
            status = "DEVIATED";
        } else {
            status = "DIVERGED";
        }
        return new ExternalVoltageComparison(
            externalVoltage,
            thirdRailState.tractionCurrentAmps(),
            thirdRailState.tractionPowerWatts(),
            deviation,
            deviationPercent,
            status,
            thirdRailState.supportReason()
        );
    }

    public List<PowerConstraint> constraintsForTrains(List<TrainState> trains, List<PowerSectionState> sections) {
        return trains.stream()
            .map(train -> {
                PowerSectionState section = sectionStateAt(sections, train.positionMeters());
                boolean energized = currentCollectionAvailable(section.status());
                return new PowerConstraint(
                    train.id(),
                    section.id(),
                    section.voltage(),
                    energized ? section.availablePowerWatts() : 0,
                    energized,
                    deratingFactor(section.status()),
                    energized,
                    regenAvailable(section.status()),
                    "ENERGIZED".equals(section.status()) ? "NORMAL" : section.status()
                );
            })
            .toList();
    }

    private Map<String, List<PowerNetworkStateSnapshot.IsolatorSnapshot>> buildIsolatorsBySection(
        PowerNetworkStateSnapshot snapshot
    ) {
        Map<String, String> powerSectionByThirdRail = infrastructureCatalog.powerData().thirdRailSections().stream()
            .collect(Collectors.toMap(
                OperationalPowerData.ThirdRailSectionDefinition::id,
                OperationalPowerData.ThirdRailSectionDefinition::powerSectionId,
                (left, right) -> left
            ));
        Map<String, List<PowerNetworkStateSnapshot.IsolatorSnapshot>> result = new LinkedHashMap<>();
        for (PowerNetworkStateSnapshot.IsolatorSnapshot isolator : snapshot.isolators()) {
            String sectionId = powerSectionByThirdRail.getOrDefault(isolator.thirdRailSectionId(), isolator.thirdRailSectionId());
            result.computeIfAbsent(sectionId, ignored -> new ArrayList<>()).add(isolator);
        }
        return result;
    }

    private PowerNetworkStateSnapshot.StrayCurrentSnapshot higherRisk(
        PowerNetworkStateSnapshot.StrayCurrentSnapshot left,
        PowerNetworkStateSnapshot.StrayCurrentSnapshot right
    ) {
        return riskRank(right.riskLevel()) > riskRank(left.riskLevel()) ? right : left;
    }

    private int riskRank(String riskLevel) {
        return switch (riskLevel) {
            case "CRITICAL" -> 4;
            case "WARNING" -> 3;
            case "ATTENTION" -> 2;
            default -> 1;
        };
    }

    private String breakerStatus(
        OperationalPowerData.PowerSectionDefinition section,
        PowerNetworkStateSnapshot.SubstationSnapshot substationState,
        String injectedFault
    ) {
        if ("BREAKER_TRIP".equals(injectedFault)) {
            return "TRIPPED";
        }
        if (substationState == null) {
            return section.breakerStatus();
        }
        return substationState.devices().stream()
            .filter(device -> "DC_BREAKER".equals(device.deviceType()) && device.affectsSectionIds().contains(section.id()))
            .findFirst()
            .map(device -> switch (device.state()) {
                case "TRIPPED" -> "TRIPPED";
                case "OPEN" -> "OPEN";
                default -> "CLOSED";
            })
            .orElse(section.breakerStatus());
    }

    private String isolatorStatus(List<PowerNetworkStateSnapshot.IsolatorSnapshot> isolators, String injectedFault) {
        if ("ISOLATED".equals(injectedFault)) {
            return "OPEN";
        }
        return isolators.stream().anyMatch(isolator -> "OPEN".equals(isolator.state())) ? "OPEN" : "CLOSED";
    }

    private String substationAvailability(PowerNetworkStateSnapshot.SubstationSnapshot snapshot) {
        return snapshot == null ? "AVAILABLE" : snapshot.availability();
    }

    private String maintenanceState(OperationalPowerData.PowerSectionDefinition section, String lockoutOverride) {
        if ("LOCKED_OUT".equals(lockoutOverride)) {
            return "LOCKED_OUT";
        }
        if ("GROUNDED".equals(lockoutOverride)) {
            return "GROUNDED";
        }
        return section.maintenanceState();
    }

    private String resolveStatus(
        String sectionId,
        double voltage,
        double current,
        String breakerStatus,
        String isolatorStatus,
        String maintenanceState,
        String lockoutState,
        String supplyMode,
        String substationAvailability,
        PowerNetworkStateSnapshot.ThirdRailSectionSnapshot thirdRailState,
        String injectedFault
    ) {
        if ("MAINTENANCE_LOCK".equals(injectedFault) || "LOCKED_OUT".equals(lockoutState) || "GROUNDED".equals(maintenanceState)) {
            return "MAINTENANCE_LOCKED";
        }
        if ("OUTAGE".equals(supplyMode) || "OUT_OF_SERVICE".equals(substationAvailability)) {
            return "DEENERGIZED";
        }
        if ("OPEN".equals(isolatorStatus)) {
            return "ISOLATED";
        }
        if ("BREAKER_TRIP".equals(injectedFault) || "TRIPPED".equals(breakerStatus)) {
            return "TRIPPED";
        }
        if ("DEENERGIZED".equals(injectedFault) || "OPEN".equals(breakerStatus)) {
            return "DEENERGIZED";
        }
        if (thirdRailState != null && "DEENERGIZED".equals(thirdRailState.energizationState())) {
            return "DEENERGIZED";
        }
        if ("OVERCURRENT".equals(injectedFault) || current > infrastructureCatalog.powerData().overCurrentThresholdAmps()) {
            return "OVERCURRENT";
        }
        if (voltage < infrastructureCatalog.powerData().cutoffVoltage()) {
            return "DEENERGIZED";
        }
        if ("UNDERVOLTAGE".equals(injectedFault) || voltage < infrastructureCatalog.powerData().minimumVoltage()) {
            return "UNDERVOLTAGE";
        }
        return "ENERGIZED";
    }

    private double sectionResistance(OperationalPowerData.PowerSectionDefinition section) {
        return Math.max(0, section.resistanceOhmPerMeter()) * Math.max(0, section.endMeters() - section.startMeters());
    }

    private Map<String, SectionLoad> aggregateLoads(List<VehiclePhysicsOutput> outputs) {
        Map<String, SectionLoad> loads = new HashMap<>();
        for (VehiclePhysicsOutput output : outputs) {
            String sectionId = sectionDefinitionAt(infrastructureCatalog.powerData().sections(), output.newPositionMeters()).id();
            loads.merge(
                sectionId,
                new SectionLoad(
                    output.railCurrentAmps(),
                    output.tractionPowerWatts(),
                    output.regenPowerWatts(),
                    List.of(output.trainId())
                ),
                SectionLoad::merge
            );
        }
        return loads;
    }

    private OperationalPowerData.PowerSectionDefinition sectionDefinitionAt(
        List<OperationalPowerData.PowerSectionDefinition> sections,
        double positionMeters
    ) {
        return sections.stream()
            .filter(section -> positionMeters >= section.startMeters() && positionMeters < section.endMeters())
            .findFirst()
            .orElse(sections.get(sections.size() - 1));
    }

    private PowerSectionState sectionStateAt(List<PowerSectionState> sections, double positionMeters) {
        return sections.stream()
            .filter(section -> positionMeters >= section.startMeters() && positionMeters < section.endMeters())
            .findFirst()
            .orElse(sections.get(sections.size() - 1));
    }

    private boolean currentCollectionAvailable(String status) {
        return "ENERGIZED".equals(status) || "UNDERVOLTAGE".equals(status) || "OVERCURRENT".equals(status);
    }

    private boolean regenAvailable(String status) {
        return "ENERGIZED".equals(status) || "UNDERVOLTAGE".equals(status);
    }

    private double deratingFactor(String status) {
        return switch (status) {
            case "UNDERVOLTAGE" -> 0.5;
            case "OVERCURRENT" -> 0.8;
            default -> currentCollectionAvailable(status) ? 1.0 : 0.0;
        };
    }

    private double supplyModeFactor(String supplyMode) {
        return switch (supplyMode) {
            case "SINGLE_END" -> 0.65;
            case "CROSS_FEED" -> 0.75;
            case "OUTAGE" -> 0.0;
            default -> 1.0;
        };
    }

    private String protectionState(String status) {
        return switch (status) {
            case "TRIPPED" -> "TRIPPED";
            case "OVERCURRENT" -> "OVERCURRENT";
            case "UNDERVOLTAGE" -> "UNDERVOLTAGE";
            default -> "NORMAL";
        };
    }

    private record SectionLoad(
        double currentAmps,
        double tractionPowerWatts,
        double regenPowerWatts,
        List<String> trainIds
    ) {
        static SectionLoad empty() {
            return new SectionLoad(0, 0, 0, List.of());
        }

        SectionLoad merge(SectionLoad other) {
            List<String> mergedTrainIds = new ArrayList<>(trainIds);
            mergedTrainIds.addAll(other.trainIds);
            return new SectionLoad(
                currentAmps + other.currentAmps,
                tractionPowerWatts + other.tractionPowerWatts,
                regenPowerWatts + other.regenPowerWatts,
                mergedTrainIds
            );
        }
    }

    private record ExternalVoltageComparison(
        double externalVoltage,
        double externalCurrent,
        double externalLoadWatts,
        double voltageDeviation,
        double voltageDeviationPercent,
        String status,
        String supportReason
    ) {
    }
}
