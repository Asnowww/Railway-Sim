package com.railwaysim.power.external;

import java.time.Instant;
import java.util.List;

public record PowerNetworkBootstrapRequest(
    Instant generatedAt,
    String lineId,
    String lineName,
    List<TopologySegment> topologySegments,
    List<SectionBinding> sectionBindings,
    List<SubstationBootstrap> substations,
    List<IsolatorBootstrap> isolators,
    List<StrayMonitorBootstrap> strayCurrentMonitors
) {
    public PowerNetworkBootstrapRequest {
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        topologySegments = List.copyOf(topologySegments);
        sectionBindings = List.copyOf(sectionBindings);
        substations = List.copyOf(substations);
        isolators = List.copyOf(isolators);
        strayCurrentMonitors = List.copyOf(strayCurrentMonitors);
    }

    public record TopologySegment(
        String id,
        int rawSegmentId,
        double startMeters,
        double endMeters,
        String fromNodeId,
        String toNodeId,
        String track
    ) {
    }

    public record SectionBinding(
        String powerSectionId,
        String thirdRailSectionId,
        String substationId,
        String feederId,
        double startMeters,
        double endMeters,
        List<String> isolatorIds
    ) {
        public SectionBinding {
            isolatorIds = List.copyOf(isolatorIds);
        }
    }

    public record SubstationBootstrap(
        String id,
        String name,
        String supplyMode,
        double startMeters,
        double endMeters,
        List<DeviceBootstrap> devices,
        List<String> sectionIds
    ) {
        public SubstationBootstrap {
            devices = List.copyOf(devices);
            sectionIds = List.copyOf(sectionIds);
        }
    }

    public record DeviceBootstrap(
        String id,
        String name,
        String deviceType,
        String defaultState,
        double ratedVoltage,
        double ratedCurrentAmps,
        List<String> affectsSectionIds
    ) {
        public DeviceBootstrap {
            affectsSectionIds = List.copyOf(affectsSectionIds);
        }
    }

    public record IsolatorBootstrap(
        String id,
        String name,
        String thirdRailSectionId,
        double positionMeters,
        String defaultState
    ) {
    }

    public record StrayMonitorBootstrap(
        String id,
        String name,
        String sectionId,
        String returnCurrentDeviceId,
        double positionMeters,
        double normalMinPotentialVolts,
        double normalMaxPotentialVolts
    ) {
    }
}
