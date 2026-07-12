package com.railwaysim.power;

import com.railwaysim.power.external.ExternalPowerNetworkHealth;
import com.railwaysim.power.external.PowerNetworkEventPayload;
import com.railwaysim.power.external.PowerNetworkOperationRequest;
import com.railwaysim.power.external.PowerNetworkOperationResult;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.event.PowerFaultStateChangedEvent;
import com.railwaysim.simulation.event.PowerLimitTriggeredEvent;
import com.railwaysim.simulation.event.PowerMaintenanceLockChangedEvent;
import com.railwaysim.simulation.event.RegenerativeEnergyAbsorbedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.ThirdRailVoltageChangedEvent;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PowerService {

    private final PowerTopologyService powerTopologyService;
    private final PowerIntegrationService powerIntegrationService;
    private final PowerConstraintService powerConstraintService;
    private final RealtimeStateCache realtimeStateCache;
    private final SimpleEventBus eventBus;
    private final Map<String, String> injectedFaultBySection = new LinkedHashMap<>();
    private final Map<String, String> maintenanceLockBySection = new LinkedHashMap<>();
    private final Map<String, String> supplyModeBySection = new LinkedHashMap<>();
    private final List<PowerSectionEvent> sectionEvents = new ArrayList<>();
    private List<PowerSectionState> sections = List.of();

    public PowerService(
        PowerTopologyService powerTopologyService,
        PowerIntegrationService powerIntegrationService,
        PowerConstraintService powerConstraintService,
        RealtimeStateCache realtimeStateCache,
        SimpleEventBus eventBus
    ) {
        this.powerTopologyService = powerTopologyService;
        this.powerIntegrationService = powerIntegrationService;
        this.powerConstraintService = powerConstraintService;
        this.realtimeStateCache = realtimeStateCache;
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        injectedFaultBySection.clear();
        maintenanceLockBySection.clear();
        supplyModeBySection.clear();
        sectionEvents.clear();
        sections = powerConstraintService.initializeStates(powerTopologyService.defaultSnapshot());
        realtimeStateCache.updatePowerSections(sections);
    }

    public synchronized List<PowerConstraint> constraintsForTrains(List<TrainState> trains) {
        return powerConstraintService.constraintsForTrains(trains, sections);
    }

    /** Prepares the external topology before 9300 requests its first power constraint. */
    public synchronized void prepareExternalNetwork() {
        powerIntegrationService.ensureExternalBootstrap();
    }

    public synchronized void updateFromVehicleOutputs(List<VehiclePhysicsOutput> outputs) {
        List<PowerSectionLoadSnapshot> loads = powerConstraintService.loadSnapshots(outputs);
        sections = powerConstraintService.calculateStates(
            outputs,
            powerIntegrationService.refreshSnapshot(loads),
            injectedFaultBySection,
            maintenanceLockBySection,
            supplyModeBySection
        );
        realtimeStateCache.updatePowerSections(sections);
        publishPowerEvents(sections);
    }

    public synchronized List<PowerSectionState> states() {
        return List.copyOf(sections);
    }

    public synchronized List<SubstationStateView> substations() {
        Map<String, List<PowerSectionState>> sectionsBySubstation = new LinkedHashMap<>();
        for (PowerSectionState section : sections) {
            sectionsBySubstation.computeIfAbsent(section.substationId(), ignored -> new ArrayList<>()).add(section);
        }
        return powerIntegrationService.latestSnapshot().substations().stream()
            .map(substation -> new SubstationStateView(
                substation.id(),
                substation.name(),
                substation.supplyMode(),
                substation.availability(),
                substation.devices().stream()
                    .map(device -> new SubstationDeviceView(
                        device.id(),
                        device.name(),
                        device.deviceType(),
                        device.state(),
                        device.available(),
                        device.affectsSectionIds()
                    ))
                    .toList(),
                sectionsBySubstation.getOrDefault(substation.id(), List.of()).stream()
                    .map(PowerSectionState::id)
                    .toList(),
                powerIntegrationService.latestSnapshot().dataQuality(),
                powerIntegrationService.latestSnapshot().sourceTimestamp()
            ))
            .toList();
    }

    public synchronized List<IsolatorStateView> isolators() {
        return powerIntegrationService.latestSnapshot().isolators().stream()
            .map(isolator -> new IsolatorStateView(
                isolator.id(),
                isolator.thirdRailSectionId(),
                isolator.state(),
                powerIntegrationService.latestSnapshot().dataQuality(),
                powerIntegrationService.latestSnapshot().sourceTimestamp()
            ))
            .toList();
    }

    public synchronized List<StrayCurrentRiskView> strayCurrentRisks() {
        return powerIntegrationService.latestSnapshot().strayCurrentMonitors().stream()
            .map(point -> new StrayCurrentRiskView(
                point.id(),
                point.sectionId(),
                point.cabinetState(),
                point.polarizedPotentialVolts(),
                point.riskLevel(),
                point.riskReason(),
                point.suggestedAction(),
                powerIntegrationService.latestSnapshot().dataQuality(),
                powerIntegrationService.latestSnapshot().sourceTimestamp()
            ))
            .toList();
    }

    public ExternalPowerNetworkHealth externalHealth() {
        return powerIntegrationService.health();
    }

    public com.railwaysim.power.external.PowerNetworkStateSnapshot externalSnapshot() {
        return powerIntegrationService.latestSnapshot();
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
        sections = powerConstraintService.calculateStates(
            List.of(),
            powerIntegrationService.latestSnapshot(),
            injectedFaultBySection,
            maintenanceLockBySection,
            supplyModeBySection
        );
        realtimeStateCache.updatePowerSections(sections);
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
        sections = powerConstraintService.calculateStates(
            List.of(),
            powerIntegrationService.latestSnapshot(),
            injectedFaultBySection,
            maintenanceLockBySection,
            supplyModeBySection
        );
        realtimeStateCache.updatePowerSections(sections);
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
        sections = powerConstraintService.calculateStates(
            List.of(),
            powerIntegrationService.latestSnapshot(),
            injectedFaultBySection,
            maintenanceLockBySection,
            supplyModeBySection
        );
        realtimeStateCache.updatePowerSections(sections);
    }

    public synchronized void setSupplyMode(String sectionId, String supplyMode) {
        supplyModeBySection.put(sectionId, supplyMode);
        sections = powerConstraintService.calculateStates(
            List.of(),
            powerIntegrationService.latestSnapshot(),
            injectedFaultBySection,
            maintenanceLockBySection,
            supplyModeBySection
        );
        realtimeStateCache.updatePowerSections(sections);
    }

    public synchronized PowerSectionState section(String sectionId) {
        return sections.stream()
            .filter(section -> section.id().equals(sectionId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Power section not found: " + sectionId));
    }

    public synchronized List<PowerSectionEvent> eventsForSection(String sectionId) {
        List<PowerSectionEvent> externalEvents = powerIntegrationService.events().stream()
            .filter(event -> sectionId.equals(event.targetId()))
            .map(event -> new PowerSectionEvent(
                sectionId,
                event.eventType(),
                event.targetType(),
                event.detail(),
                eventLevel(event.level()),
                affectedTrains(sectionId),
                event.occurredAt()
            ))
            .toList();
        List<PowerSectionEvent> result = new ArrayList<>(sectionEvents.stream()
            .filter(event -> event.sectionId().equals(sectionId))
            .toList());
        result.addAll(externalEvents);
        return result;
    }

    public synchronized List<PowerNetworkEventPayload> externalEvents() {
        return powerIntegrationService.events();
    }

    public synchronized PowerNetworkOperationResult operate(PowerNetworkOperationRequest request) {
        return powerIntegrationService.operate(request);
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
                    "BRAKE_RESISTOR",
                    now
                ));
            }
        }
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

    private List<String> affectedTrains(String sectionId) {
        return sections.stream()
            .filter(section -> section.id().equals(sectionId))
            .findFirst()
            .map(PowerSectionState::affectedTrainIds)
            .orElse(List.of());
    }

    private int levelForFault(String faultType) {
        return switch (faultType) {
            case "DEENERGIZED", "BREAKER_TRIP", "MAINTENANCE_LOCK", "ISOLATED" -> 3;
            case "UNDERVOLTAGE", "OVERCURRENT" -> 2;
            default -> 2;
        };
    }

    private int eventLevel(String level) {
        return switch (level) {
            case "CRITICAL", "ERROR" -> 3;
            case "WARNING", "WARN" -> 2;
            default -> 1;
        };
    }

    private String powerLimitReason(String status) {
        return switch (status) {
            case "DEENERGIZED" -> "接触轨失电，车辆牵引切除";
            case "TRIPPED" -> "断路器跳闸，供电分区退出运行";
            case "OVERCURRENT" -> "馈线过流，供电能力受限";
            case "MAINTENANCE_LOCKED" -> "供电分区处于检修闭锁状态";
            case "ISOLATED" -> "隔离开关断开，供电区段已隔离";
            default -> "接触轨电压低于最低牵引阈值";
        };
    }
}
