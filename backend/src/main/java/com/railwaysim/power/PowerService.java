package com.railwaysim.power;

import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.event.PowerLimitTriggeredEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.ThirdRailVoltageChangedEvent;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
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
        sections.clear();
        sections.addAll(powerData.sections().stream()
            .map(section -> new PowerSectionState(
                section.id(),
                section.name(),
                section.startMeters(),
                section.endMeters(),
                section.substationVoltage(),
                0,
                section.energized() ? "ENERGIZED" : "DEENERGIZED"
            ))
            .toList());
    }

    public synchronized List<PowerConstraint> constraintsForTrains(List<TrainState> trains) {
        OperationalPowerData powerData = infrastructureCatalog.powerData();
        return trains.stream()
            .map(train -> {
                PowerSectionState section = sectionAt(train.positionMeters());
                boolean energized = !"DEENERGIZED".equals(section.status());
                double deratingFactor = "UNDERVOLTAGE".equals(section.status()) ? 0.5 : 1.0;
                double availablePower = energized
                    ? section.voltage() * powerData.maxTractionCurrentAmps() * deratingFactor
                    : 0;
                return new PowerConstraint(train.id(), section.id(), section.voltage(), availablePower, energized);
            })
            .toList();
    }

    public synchronized void updateFromVehicleOutputs(List<VehiclePhysicsOutput> outputs) {
        Map<String, Double> currentBySection = outputs.stream()
            .collect(Collectors.groupingBy(
                output -> sectionAt(output.newPositionMeters()).id(),
                Collectors.summingDouble(VehiclePhysicsOutput::railCurrentAmps)
            ));
        Map<String, OperationalPowerData.PowerSectionDefinition> sectionDefinitionById = infrastructureCatalog.powerData().sections().stream()
            .collect(Collectors.toMap(
                OperationalPowerData.PowerSectionDefinition::id,
                Function.identity(),
                (left, right) -> left
            ));
        List<PowerSectionState> updated = sections.stream()
            .map(section -> {
                double current = currentBySection.getOrDefault(section.id(), 0.0);
                OperationalPowerData.PowerSectionDefinition sectionDefinition = sectionDefinitionById.get(section.id());
                double substationVoltage = sectionDefinition == null
                    ? section.voltage()
                    : sectionDefinition.substationVoltage();
                double voltage = Math.max(
                    0,
                    substationVoltage - current * infrastructureCatalog.powerData().currentToVoltageDrop()
                );
                String status = resolveStatus(voltage);
                return new PowerSectionState(
                    section.id(),
                    section.name(),
                    section.startMeters(),
                    section.endMeters(),
                    voltage,
                    current,
                    status
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

    private PowerSectionState sectionAt(double positionMeters) {
        return sections.stream()
            .filter(section -> positionMeters >= section.startMeters() && positionMeters < section.endMeters())
            .findFirst()
            .orElse(sections.get(sections.size() - 1));
    }

    private String resolveStatus(double voltage) {
        if (voltage < infrastructureCatalog.powerData().cutoffVoltage()) {
            return "DEENERGIZED";
        }
        if (voltage < infrastructureCatalog.powerData().minimumVoltage()) {
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
                    "DEENERGIZED".equals(section.status()) ? "接触轨电压低于切除阈值" : "接触轨电压低于最低牵引阈值",
                    now
                ));
            }
        }
    }
}
