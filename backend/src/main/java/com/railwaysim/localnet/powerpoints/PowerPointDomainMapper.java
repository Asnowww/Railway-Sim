package com.railwaysim.localnet.powerpoints;

import com.railwaysim.power.PowerService;
import com.railwaysim.power.external.PowerNetworkOperationRequest;
import com.railwaysim.power.external.PowerNetworkOperationResult;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PowerPointDomainMapper {

    private static final String CONFIRM_TOKEN = "SIMULATION_CONFIRM";

    private final PowerService powerService;

    public PowerPointDomainMapper(PowerService powerService) {
        this.powerService = powerService;
    }

    public PowerNetworkStateSnapshot toSnapshot(
        List<PowerPointDefinition> definitions,
        List<PowerPointValue> values
    ) {
        Map<String, PowerPointDefinition> definitionsByPoint = definitions.stream()
            .collect(LinkedHashMap::new, (map, definition) -> map.put(definition.pointId(), definition), Map::putAll);
        Map<String, SectionAccumulator> sections = new LinkedHashMap<>();
        Map<String, IsolatorAccumulator> isolators = new LinkedHashMap<>();
        Map<String, StrayAccumulator> strayPoints = new LinkedHashMap<>();
        Instant timestamp = Instant.now();
        for (PowerPointValue value : values) {
            PowerPointDefinition definition = definitionsByPoint.get(value.pointId());
            if (definition == null || !definition.readPoint()) {
                continue;
            }
            timestamp = value.sourceTimestamp();
            switch (definition.targetType()) {
                case "THIRD_RAIL_SECTION", "POWER_SECTION" -> sections
                    .computeIfAbsent(definition.targetId(), SectionAccumulator::new)
                    .apply(definition, value);
                case "ISOLATOR" -> isolators
                    .computeIfAbsent(definition.targetId(), IsolatorAccumulator::new)
                    .apply(definition, value);
                case "STRAY_CURRENT" -> strayPoints
                    .computeIfAbsent(definition.targetId(), StrayAccumulator::new)
                    .apply(definition, value);
                default -> {
                    // Unknown target types stay visible in packet audit but do not affect the synthetic snapshot.
                }
            }
        }
        return new PowerNetworkStateSnapshot(
            timestamp,
            "ONLINE",
            values.stream().allMatch(value -> "GOOD".equals(value.quality())) ? "GOOD" : "DEGRADED",
            List.of(),
            sections.values().stream().map(SectionAccumulator::snapshot).toList(),
            isolators.values().stream().map(IsolatorAccumulator::snapshot).toList(),
            strayPoints.values().stream().map(StrayAccumulator::snapshot).toList(),
            List.of()
        );
    }

    public PowerNetworkOperationResult applyWrite(PowerPointDefinition definition, String value) {
        if (!definition.writePoint()) {
            throw new IllegalArgumentException("Power point is not writable: " + definition.pointId());
        }
        String desiredState = value == null || value.isBlank() ? definition.desiredState() : value.trim().toUpperCase();
        return powerService.operate(new PowerNetworkOperationRequest(
            definition.operationType(),
            definition.targetType(),
            definition.targetId(),
            desiredState,
            "localnet-power-points",
            "power point write " + definition.pointId(),
            "power-point-" + definition.pointId() + "-" + System.currentTimeMillis(),
            CONFIRM_TOKEN
        ));
    }

    private static class SectionAccumulator {

        private final String id;
        private double voltage = 1500;
        private double current;
        private double loadWatts;
        private double regenWatts;
        private String state = "ENERGIZED";
        private String reason = "power point table";

        SectionAccumulator(String id) {
            this.id = id;
        }

        void apply(PowerPointDefinition definition, PowerPointValue value) {
            String field = definition.targetField().toLowerCase();
            double scaledValue = value.asDouble(0) * definition.scale();
            switch (field) {
                case "voltage", "contactrailvoltage", "contact_rail_voltage" -> voltage = scaledValue;
                case "current", "tractioncurrent", "traction_current" -> current = scaledValue;
                case "load", "power", "tractionpower", "traction_power" -> loadWatts = scaledValue;
                case "regen", "regenpower", "regen_power" -> regenWatts = scaledValue;
                case "state", "energizationstate" -> state = value.asBoolean() ? "ENERGIZED" : "DEENERGIZED";
                default -> reason = definition.targetField() + "=" + value.value();
            }
        }

        PowerNetworkStateSnapshot.ThirdRailSectionSnapshot snapshot() {
            return new PowerNetworkStateSnapshot.ThirdRailSectionSnapshot(
                id,
                id,
                0,
                0,
                state,
                "AVAILABLE",
                "DOUBLE_END",
                voltage,
                current,
                loadWatts,
                regenWatts,
                regenWatts,
                reason
            );
        }
    }

    private static class IsolatorAccumulator {

        private final String id;
        private String state = "CLOSED";

        IsolatorAccumulator(String id) {
            this.id = id;
        }

        void apply(PowerPointDefinition definition, PowerPointValue value) {
            state = value.asBoolean() ? "CLOSED" : "OPEN";
        }

        PowerNetworkStateSnapshot.IsolatorSnapshot snapshot() {
            return new PowerNetworkStateSnapshot.IsolatorSnapshot(id, "", state);
        }
    }

    private static class StrayAccumulator {

        private final String id;
        private String sectionId = "";
        private double potential;
        private String riskLevel = "NORMAL";

        StrayAccumulator(String id) {
            this.id = id;
        }

        void apply(PowerPointDefinition definition, PowerPointValue value) {
            if ("sectionId".equalsIgnoreCase(definition.targetField())) {
                sectionId = value.value();
                return;
            }
            potential = value.asDouble(0) * definition.scale();
            riskLevel = Math.abs(potential) >= 3.0 ? "WARNING" : "NORMAL";
        }

        PowerNetworkStateSnapshot.StrayCurrentSnapshot snapshot() {
            return new PowerNetworkStateSnapshot.StrayCurrentSnapshot(
                id,
                sectionId,
                "NORMAL",
                potential,
                riskLevel,
                "power point table",
                riskLevel.equals("NORMAL") ? "" : "inspect return current path"
            );
        }
    }
}
