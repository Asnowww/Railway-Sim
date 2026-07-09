package com.railwaysim.power.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PowerNetworkStateSnapshot(
    Instant sourceTimestamp,
    String heartbeatStatus,
    String dataQuality,
    List<SubstationSnapshot> substations,
    List<ThirdRailSectionSnapshot> thirdRailSections,
    List<IsolatorSnapshot> isolators,
    List<StrayCurrentSnapshot> strayCurrentMonitors,
    List<PowerNetworkEventPayload> events
) {
    public PowerNetworkStateSnapshot {
        sourceTimestamp = sourceTimestamp == null ? Instant.now() : sourceTimestamp;
        heartbeatStatus = heartbeatStatus == null || heartbeatStatus.isBlank() ? "UNKNOWN" : heartbeatStatus;
        dataQuality = dataQuality == null || dataQuality.isBlank() ? "UNKNOWN" : dataQuality;
        substations = safeCopy(substations);
        thirdRailSections = safeCopy(thirdRailSections);
        isolators = safeCopy(isolators);
        strayCurrentMonitors = safeCopy(strayCurrentMonitors);
        events = safeCopy(events);
    }

    private static <T> List<T> safeCopy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubstationSnapshot(
        String id,
        String name,
        String supplyMode,
        String availability,
        List<DeviceSnapshot> devices
    ) {
        public SubstationSnapshot {
            supplyMode = supplyMode == null || supplyMode.isBlank() ? "DOUBLE_END" : supplyMode;
            availability = availability == null || availability.isBlank() ? "AVAILABLE" : availability;
            devices = safeCopy(devices);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceSnapshot(
        String id,
        String name,
        String deviceType,
        String state,
        boolean available,
        List<String> affectsSectionIds
    ) {
        public DeviceSnapshot {
            state = state == null || state.isBlank() ? "UNKNOWN" : state;
            affectsSectionIds = safeCopy(affectsSectionIds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThirdRailSectionSnapshot(
        String id,
        String powerSectionId,
        double startMeters,
        double endMeters,
        String energizationState,
        String feederState,
        String recommendedSupplyMode,
        double contactRailVoltage,
        double tractionCurrentAmps,
        double tractionPowerWatts,
        double regenPowerWatts,
        double absorbedRegenWatts,
        String supportReason
    ) {
        public ThirdRailSectionSnapshot {
            powerSectionId = powerSectionId == null || powerSectionId.isBlank() ? id : powerSectionId;
            energizationState = energizationState == null || energizationState.isBlank() ? "ENERGIZED" : energizationState;
            feederState = feederState == null || feederState.isBlank() ? "AVAILABLE" : feederState;
            recommendedSupplyMode = recommendedSupplyMode == null || recommendedSupplyMode.isBlank()
                ? "DOUBLE_END"
                : recommendedSupplyMode;
            supportReason = supportReason == null ? "" : supportReason;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IsolatorSnapshot(
        String id,
        String thirdRailSectionId,
        String state
    ) {
        public IsolatorSnapshot {
            state = state == null || state.isBlank() ? "CLOSED" : state;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StrayCurrentSnapshot(
        String id,
        String sectionId,
        String cabinetState,
        double polarizedPotentialVolts,
        String riskLevel,
        String riskReason,
        String suggestedAction
    ) {
        public StrayCurrentSnapshot {
            cabinetState = cabinetState == null || cabinetState.isBlank() ? "NORMAL" : cabinetState;
            riskLevel = riskLevel == null || riskLevel.isBlank() ? "NORMAL" : riskLevel;
            riskReason = riskReason == null ? "" : riskReason;
            suggestedAction = suggestedAction == null ? "" : suggestedAction;
        }
    }
}
