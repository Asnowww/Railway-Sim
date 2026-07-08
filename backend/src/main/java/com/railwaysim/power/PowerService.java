package com.railwaysim.power;

import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.event.PowerLimitTriggeredEvent;
import com.railwaysim.simulation.event.PowerFaultStateChangedEvent;
import com.railwaysim.simulation.event.PowerMaintenanceLockChangedEvent;
import com.railwaysim.simulation.event.RegenerativeEnergyAbsorbedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.ThirdRailVoltageChangedEvent;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class PowerService {

    private final List<PowerSectionState> sections = new ArrayList<>();
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final RealtimeStateCache realtimeStateCache;
    private final SimpleEventBus eventBus;
    private final Map<String, String> injectedFaultBySection = new HashMap<>();
    private final Map<String, String> maintenanceLockBySection = new HashMap<>();
    private final Map<String, String> supplyModeBySection = new HashMap<>();
    private final List<PowerSectionEvent> sectionEvents = new ArrayList<>();

    public PowerService(
        StaticInfrastructureCatalog infrastructureCatalog,
        RealtimeStateCache realtimeStateCache,
        SimpleEventBus eventBus
    ) {
        this.infrastructureCatalog = infrastructureCatalog;
        this.realtimeStateCache = realtimeStateCache;
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        OperationalPowerData powerData = infrastructureCatalog.powerData();
        injectedFaultBySection.clear();
        maintenanceLockBySection.clear();
        supplyModeBySection.clear();
        sectionEvents.clear();
        Instant now = Instant.now();
        sections.clear();
        sections.addAll(powerData.sections().stream()
            .map(section -> new PowerSectionState(
                section.id(),
                section.name(),
                section.substationId(),
                section.feederId(),
                section.startMeters(),
                section.endMeters(),
                section.substationVoltage(),
                0,
                section.energized() ? "ENERGIZED" : "DEENERGIZED",
                0,
                0,
                0,
                0,
                section.energized() ? section.substationVoltage() * powerData.maxTractionCurrentAmps() : 0,
                section.breakerStatus(),
                "NORMAL",
                section.maintenanceState(),
                section.lockoutState(),
                List.of(),
                "GOOD",
                now
            ))
            .toList());
    }

    public synchronized List<PowerConstraint> constraintsForTrains(List<TrainState> trains) {
        return trains.stream()
            .map(train -> {
                PowerSectionState section = sectionAt(train.positionMeters());
                boolean currentCollectionAvailable = currentCollectionAvailable(section.status());
                double deratingFactor = deratingFactor(section.status());
                double availablePower = currentCollectionAvailable ? section.availablePowerWatts() : 0;
                return new PowerConstraint(
                    train.id(),
                    section.id(),
                    section.voltage(),
                    availablePower,
                    currentCollectionAvailable,
                    deratingFactor,
                    currentCollectionAvailable,
                    regenAvailable(section.status()),
                    constraintReason(section.status())
                );
            })
            .toList();
    }

    public synchronized void updateFromVehicleOutputs(List<VehiclePhysicsOutput> outputs) {
        Map<String, SectionLoad> loadBySection = aggregateLoads(outputs);
        Map<String, OperationalPowerData.PowerSectionDefinition> sectionDefinitionById = infrastructureCatalog.powerData().sections().stream()
            .collect(Collectors.toMap(
                OperationalPowerData.PowerSectionDefinition::id,
                Function.identity(),
                (left, right) -> left
            ));
        Instant now = Instant.now();
        List<PowerSectionState> updated = sections.stream()
            .map(section -> {
                SectionLoad load = loadBySection.getOrDefault(section.id(), SectionLoad.empty());
                OperationalPowerData.PowerSectionDefinition sectionDefinition = sectionDefinitionById.get(section.id());
                double substationVoltage = sectionDefinition == null
                    ? section.voltage()
                    : sectionDefinition.substationVoltage();
                String supplyMode = supplyModeFor(sectionDefinition, section);
                double absorbedRegenPower = infrastructureCatalog.powerData().sameSectionAbsorbFirst()
                    ? Math.min(load.tractionPowerWatts(), load.regenPowerWatts())
                    : 0;
                double unabsorbedRegenPower = Math.max(0, load.regenPowerWatts() - absorbedRegenPower);
                double absorbedCurrent = substationVoltage > 1 ? absorbedRegenPower / substationVoltage : 0;
                double netCurrent = Math.max(0, load.currentAmps() - absorbedCurrent);
                double voltageDropPerAmp = infrastructureCatalog.powerData().currentToVoltageDrop() +
                    sectionResistance(sectionDefinition);
                double voltage = Math.max(
                    0,
                    substationVoltage - netCurrent * voltageDropPerAmp
                );
                String breakerStatus = breakerStatusFor(sectionDefinition, section);
                String maintenanceState = maintenanceStateFor(sectionDefinition, section);
                String lockoutState = lockoutStateFor(sectionDefinition, section);
                String status = resolveStatus(section.id(), voltage, netCurrent, breakerStatus, maintenanceState, lockoutState);
                double availablePower = availablePowerWatts(voltage, status, supplyMode);
                return new PowerSectionState(
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
                    breakerStatus,
                    protectionState(status),
                    maintenanceState,
                    lockoutState,
                    load.trainIds(),
                    "GOOD",
                    now
                );
            })
            .toList();
        sections.clear();
        sections.addAll(updated);
        realtimeStateCache.updatePowerSections(updated);
        publishPowerEvents(updated);
    }

    public synchronized List<PowerSectionState> states() {
        return List.copyOf(sections);
    }

    public synchronized void injectPowerFault(String sectionId, String faultType) {
        injectedFaultBySection.put(sectionId, faultType);
        Instant now = Instant.now();
        eventBus.publish(new PowerFaultStateChangedEvent(sectionId, faultType, "INJECTED", now));
        sectionEvents.add(new PowerSectionEvent(
            sectionId,
            "POWER_FAULT",
            "INJECTED",
            faultType,
            levelForFault(faultType),
            affectedTrains(sectionId),
            now
        ));
        refreshSectionState(sectionId);
    }

    public synchronized void clearPowerFault(String sectionId) {
        String clearedFault = injectedFaultBySection.remove(sectionId);
        Instant now = Instant.now();
        eventBus.publish(new PowerFaultStateChangedEvent(
            sectionId,
            clearedFault == null ? "NONE" : clearedFault,
            "CLEARED",
            now
        ));
        sectionEvents.add(new PowerSectionEvent(
            sectionId,
            "POWER_FAULT",
            "CLEARED",
            clearedFault == null ? "NONE" : clearedFault,
            1,
            affectedTrains(sectionId),
            now
        ));
        refreshSectionState(sectionId);
    }

    public synchronized void setMaintenanceLock(String sectionId, String lockoutState) {
        maintenanceLockBySection.put(sectionId, lockoutState);
        Instant now = Instant.now();
        eventBus.publish(new PowerMaintenanceLockChangedEvent(sectionId, lockoutState, maintenanceStateForLock(lockoutState), now));
        sectionEvents.add(new PowerSectionEvent(
            sectionId,
            "MAINTENANCE_LOCK",
            lockoutState,
            maintenanceStateForLock(lockoutState),
            "LOCKED_OUT".equals(lockoutState) || "GROUNDED".equals(lockoutState) ? 2 : 1,
            affectedTrains(sectionId),
            now
        ));
        refreshSectionState(sectionId);
    }

    public synchronized void setSupplyMode(String sectionId, String supplyMode) {
        supplyModeBySection.put(sectionId, supplyMode);
        refreshSectionState(sectionId);
    }

    public synchronized PowerSectionState section(String sectionId) {
        return sections.stream()
            .filter(section -> section.id().equals(sectionId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Power section not found: " + sectionId));
    }

    public synchronized List<PowerSectionEvent> eventsForSection(String sectionId) {
        return sectionEvents.stream()
            .filter(event -> event.sectionId().equals(sectionId))
            .toList();
    }

    private PowerSectionState sectionAt(double positionMeters) {
        return sections.stream()
            .filter(section -> positionMeters >= section.startMeters() && positionMeters < section.endMeters())
            .findFirst()
            .orElse(sections.get(sections.size() - 1));
    }

    private void refreshSectionState(String sectionId) {
        Map<String, OperationalPowerData.PowerSectionDefinition> sectionDefinitionById = infrastructureCatalog.powerData().sections().stream()
            .collect(Collectors.toMap(
                OperationalPowerData.PowerSectionDefinition::id,
                Function.identity(),
                (left, right) -> left
            ));
        List<PowerSectionState> updated = sections.stream()
            .map(section -> section.id().equals(sectionId)
                ? refreshSectionState(section, sectionDefinitionById.get(section.id()), Instant.now())
                : section)
            .toList();
        sections.clear();
        sections.addAll(updated);
        realtimeStateCache.updatePowerSections(updated);
    }

    private PowerSectionState refreshSectionState(
        PowerSectionState section,
        OperationalPowerData.PowerSectionDefinition sectionDefinition,
        Instant now
    ) {
        String supplyMode = supplyModeFor(sectionDefinition, section);
        String breakerStatus = breakerStatusFor(sectionDefinition, section);
        String maintenanceState = maintenanceStateFor(sectionDefinition, section);
        String lockoutState = lockoutStateFor(sectionDefinition, section);
        String status = resolveStatus(section.id(), section.voltage(), section.current(), breakerStatus, maintenanceState, lockoutState);
        return new PowerSectionState(
            section.id(),
            section.name(),
            section.substationId(),
            section.feederId(),
            section.startMeters(),
            section.endMeters(),
            section.voltage(),
            section.current(),
            status,
            section.loadWatts(),
            section.regenPowerWatts(),
            section.absorbedRegenPowerWatts(),
            section.unabsorbedRegenPowerWatts(),
            availablePowerWatts(section.voltage(), status, supplyMode),
            breakerStatus,
            protectionState(status),
            maintenanceState,
            lockoutState,
            section.affectedTrainIds(),
            section.dataQuality(),
            now
        );
    }

    private String resolveStatus(
        String sectionId,
        double voltage,
        double current,
        String breakerStatus,
        String maintenanceState,
        String lockoutState
    ) {
        String injectedFault = injectedFaultBySection.get(sectionId);
        if ("MAINTENANCE_LOCK".equals(injectedFault) || "LOCKED_OUT".equals(lockoutState) || "GROUNDED".equals(maintenanceState)) {
            return "MAINTENANCE_LOCKED";
        }
        if ("BREAKER_TRIP".equals(injectedFault) || "TRIPPED".equals(breakerStatus)) {
            return "TRIPPED";
        }
        if ("DEENERGIZED".equals(injectedFault) || "OPEN".equals(breakerStatus)) {
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

    private void publishPowerEvents(List<PowerSectionState> updated) {
        Instant now = Instant.now();
        for (PowerSectionState section : updated) {
            eventBus.publish(new ThirdRailVoltageChangedEvent(
                section.id(),
                section.voltage(),
                section.current(),
                section.status(),
                now
            ));
            if (!"ENERGIZED".equals(section.status())) {
                eventBus.publish(new PowerLimitTriggeredEvent(
                    section.id(),
                    section.voltage(),
                    powerLimitReason(section.status()),
                    now
                ));
            }
            if (section.regenPowerWatts() > 0) {
                eventBus.publish(new RegenerativeEnergyAbsorbedEvent(
                    section.id(),
                    section.regenPowerWatts(),
                    section.absorbedRegenPowerWatts(),
                    section.unabsorbedRegenPowerWatts(),
                    infrastructureCatalog.powerData().unabsorbedRegenMode(),
                    now
                ));
            }
        }
    }

    private Map<String, SectionLoad> aggregateLoads(List<VehiclePhysicsOutput> outputs) {
        Map<String, SectionLoad> loads = new HashMap<>();
        for (VehiclePhysicsOutput output : outputs) {
            String sectionId = sectionAt(output.newPositionMeters()).id();
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

    private double sectionResistance(OperationalPowerData.PowerSectionDefinition sectionDefinition) {
        if (sectionDefinition == null || sectionDefinition.resistanceOhmPerMeter() <= 0) {
            return 0;
        }
        return sectionDefinition.resistanceOhmPerMeter() * Math.max(0, sectionDefinition.endMeters() - sectionDefinition.startMeters());
    }

    private String supplyModeFor(OperationalPowerData.PowerSectionDefinition sectionDefinition, PowerSectionState section) {
        return supplyModeBySection.getOrDefault(
            section.id(),
            sectionDefinition == null ? "DOUBLE_END" : sectionDefinition.supplyMode()
        );
    }

    private String breakerStatusFor(OperationalPowerData.PowerSectionDefinition sectionDefinition, PowerSectionState section) {
        if ("BREAKER_TRIP".equals(injectedFaultBySection.get(section.id()))) {
            return "TRIPPED";
        }
        return sectionDefinition == null ? section.breakerStatus() : sectionDefinition.breakerStatus();
    }

    private String maintenanceStateFor(OperationalPowerData.PowerSectionDefinition sectionDefinition, PowerSectionState section) {
        String lockoutOverride = maintenanceLockBySection.get(section.id());
        if (lockoutOverride != null) {
            return maintenanceStateForLock(lockoutOverride);
        }
        return sectionDefinition == null ? section.maintenanceState() : sectionDefinition.maintenanceState();
    }

    private String lockoutStateFor(OperationalPowerData.PowerSectionDefinition sectionDefinition, PowerSectionState section) {
        return maintenanceLockBySection.getOrDefault(
            section.id(),
            sectionDefinition == null ? section.lockoutState() : sectionDefinition.lockoutState()
        );
    }

    private String maintenanceStateForLock(String lockoutState) {
        if ("LOCKED_OUT".equals(lockoutState)) {
            return "LOCKED_OUT";
        }
        if ("GROUNDED".equals(lockoutState)) {
            return "GROUNDED";
        }
        return "NONE";
    }

    private double availablePowerWatts(double voltage, String status, String supplyMode) {
        if (!currentCollectionAvailable(status)) {
            return 0;
        }
        return voltage * infrastructureCatalog.powerData().maxTractionCurrentAmps() * deratingFactor(status) * supplyModeFactor(supplyMode);
    }

    private double supplyModeFactor(String supplyMode) {
        return switch (supplyMode) {
            case "SINGLE_END" -> 0.65;
            case "CROSS_FEED" -> 0.75;
            case "OUTAGE" -> 0.0;
            default -> 1.0;
        };
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

    private String constraintReason(String status) {
        return "ENERGIZED".equals(status) ? "NORMAL" : status;
    }

    private String protectionState(String status) {
        return switch (status) {
            case "TRIPPED" -> "TRIPPED";
            case "OVERCURRENT" -> "OVERCURRENT";
            case "UNDERVOLTAGE" -> "UNDERVOLTAGE";
            default -> "NORMAL";
        };
    }

    private String powerLimitReason(String status) {
        return switch (status) {
            case "DEENERGIZED" -> "接触轨失电，车辆牵引切除";
            case "TRIPPED" -> "断路器跳闸，供电分区退出运行";
            case "OVERCURRENT" -> "馈线过流，供电能力受限";
            case "MAINTENANCE_LOCKED" -> "供电分区处于检修闭锁状态";
            default -> "接触轨电压低于最低牵引阈值";
        };
    }

    private List<String> affectedTrains(String sectionId) {
        return sections.stream()
            .filter(section -> section.id().equals(sectionId))
            .findFirst()
            .map(PowerSectionState::affectedTrainIds)
            .orElse(List.of());
    }

    private int levelForFault(String faultType) {
        return switch (faultType) {
            case "DEENERGIZED", "BREAKER_TRIP", "MAINTENANCE_LOCK" -> 3;
            case "UNDERVOLTAGE", "OVERCURRENT" -> 2;
            default -> 2;
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
}
