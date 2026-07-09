package com.railwaysim.power;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.external.PowerNetworkBootstrapRequest;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class PowerTopologyService {

    private final StaticInfrastructureCatalog infrastructureCatalog;

    public PowerTopologyService(StaticInfrastructureCatalog infrastructureCatalog) {
        this.infrastructureCatalog = infrastructureCatalog;
    }

    public PowerNetworkBootstrapRequest buildBootstrapRequest() {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        OperationalPowerData powerData = infrastructureCatalog.powerData();
        Map<String, OperationalPowerData.ThirdRailSectionDefinition> thirdRailByPowerSection = powerData.thirdRailSections().stream()
            .collect(Collectors.toMap(
                OperationalPowerData.ThirdRailSectionDefinition::powerSectionId,
                section -> section,
                (left, right) -> left
            ));
        return new PowerNetworkBootstrapRequest(
            Instant.now(),
            lineData.lineId(),
            lineData.lineName(),
            lineData.trackSegments().stream()
                .map(segment -> new PowerNetworkBootstrapRequest.TopologySegment(
                    segment.id(),
                    segment.rawSegmentId(),
                    segment.startMeters(),
                    segment.endMeters(),
                    segment.fromNodeId(),
                    segment.toNodeId(),
                    segment.track()
                ))
                .toList(),
            powerData.sections().stream()
                .map(section -> {
                    OperationalPowerData.ThirdRailSectionDefinition thirdRail = thirdRailByPowerSection.get(section.id());
                    return new PowerNetworkBootstrapRequest.SectionBinding(
                        section.id(),
                        thirdRail == null ? section.id() : thirdRail.id(),
                        section.substationId(),
                        section.feederId(),
                        section.startMeters(),
                        section.endMeters(),
                        thirdRail == null ? List.of() : thirdRail.isolatorIds()
                    );
                })
                .toList(),
            powerData.substations().stream()
                .map(substation -> new PowerNetworkBootstrapRequest.SubstationBootstrap(
                    substation.id(),
                    substation.name(),
                    substation.supplyMode(),
                    substation.startMeters(),
                    substation.endMeters(),
                    substation.devices().stream()
                        .map(device -> new PowerNetworkBootstrapRequest.DeviceBootstrap(
                            device.id(),
                            device.name(),
                            device.deviceType(),
                            device.defaultState(),
                            device.ratedVoltage(),
                            device.ratedCurrentAmps(),
                            device.affectsSectionIds()
                        ))
                        .toList(),
                    substation.sectionIds()
                ))
                .toList(),
            powerData.isolators().stream()
                .map(isolator -> new PowerNetworkBootstrapRequest.IsolatorBootstrap(
                    isolator.id(),
                    isolator.name(),
                    isolator.thirdRailSectionId(),
                    isolator.positionMeters(),
                    isolator.defaultState()
                ))
                .toList(),
            powerData.strayCurrentMonitorPoints().stream()
                .map(point -> new PowerNetworkBootstrapRequest.StrayMonitorBootstrap(
                    point.id(),
                    point.name(),
                    point.sectionId(),
                    point.returnCurrentDeviceId(),
                    point.positionMeters(),
                    point.normalMinPotentialVolts(),
                    point.normalMaxPotentialVolts()
                ))
                .toList()
        );
    }

    public PowerNetworkStateSnapshot defaultSnapshot() {
        OperationalPowerData powerData = infrastructureCatalog.powerData();
        return new PowerNetworkStateSnapshot(
            Instant.now(),
            "LOCAL",
            "GOOD",
            powerData.substations().stream()
                .map(substation -> new PowerNetworkStateSnapshot.SubstationSnapshot(
                    substation.id(),
                    substation.name(),
                    substation.supplyMode(),
                    substation.available() ? "AVAILABLE" : "OUT_OF_SERVICE",
                    substation.devices().stream()
                        .map(device -> new PowerNetworkStateSnapshot.DeviceSnapshot(
                            device.id(),
                            device.name(),
                            device.deviceType(),
                            device.defaultState(),
                            !"OUT_OF_SERVICE".equals(device.defaultState()),
                            device.affectsSectionIds()
                        ))
                        .toList()
                ))
                .toList(),
            powerData.thirdRailSections().stream()
                .map(section -> new PowerNetworkStateSnapshot.ThirdRailSectionSnapshot(
                    section.id(),
                    section.powerSectionId(),
                    "ENERGIZED",
                    "AVAILABLE",
                    supplyModeForSection(section.powerSectionId())
                ))
                .toList(),
            powerData.isolators().stream()
                .map(isolator -> new PowerNetworkStateSnapshot.IsolatorSnapshot(
                    isolator.id(),
                    isolator.thirdRailSectionId(),
                    isolator.defaultState()
                ))
                .toList(),
            powerData.strayCurrentMonitorPoints().stream()
                .map(point -> new PowerNetworkStateSnapshot.StrayCurrentSnapshot(
                    point.id(),
                    point.sectionId(),
                    "NORMAL",
                    0.25,
                    "NORMAL",
                    "local default snapshot",
                    "NONE"
                ))
                .toList(),
            List.of()
        );
    }

    private String supplyModeForSection(String powerSectionId) {
        return infrastructureCatalog.powerData().sections().stream()
            .filter(section -> section.id().equals(powerSectionId))
            .map(OperationalPowerData.PowerSectionDefinition::supplyMode)
            .findFirst()
            .orElse("DOUBLE_END");
    }
}
