package com.railwaysim.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PowerConfigLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public OperationalPowerData load(Path path, double lineLengthMeters) throws IOException {
        PowerConfigFile powerConfig = yamlMapper.readValue(Files.readString(path), PowerConfigFile.class);
        List<OperationalPowerData.PowerSectionDefinition> sections = new ArrayList<>();
        if (powerConfig.sections != null) {
            for (PowerSection section : powerConfig.sections) {
                sections.add(new OperationalPowerData.PowerSectionDefinition(
                    section.id,
                    section.name,
                    defaultIfBlank(section.substationId, "SS-" + section.id),
                    defaultIfBlank(section.feederId, "FD-" + section.id),
                    section.startMeters,
                    section.endMeters,
                    section.substationVoltage,
                    section.energized,
                    defaultIfBlank(section.breakerStatus, section.energized ? "CLOSED" : "OPEN"),
                    defaultIfBlank(section.isolatorStatus, "CLOSED"),
                    defaultIfBlank(section.supplyMode, section.energized ? "DOUBLE_END" : "OUTAGE"),
                    defaultIfBlank(section.maintenanceState, "NONE"),
                    defaultIfBlank(section.lockoutState, "UNLOCKED"),
                    Math.max(0, section.resistanceOhmPerMeter)
                ));
            }
        }

        if (sections.isEmpty()) {
            sections = List.of(new OperationalPowerData.PowerSectionDefinition(
                "P01",
                "全线供电分区",
                "SS-P01",
                "FD-P01",
                0,
                lineLengthMeters,
                powerConfig.nominalVoltage,
                true,
                "CLOSED",
                "CLOSED",
                "DOUBLE_END",
                "NONE",
                "UNLOCKED",
                0
            ));
        } else {
            sections = normalizeCoverage(sections, lineLengthMeters);
        }

        return new OperationalPowerData(
            powerConfig.nominalVoltage,
            powerConfig.minimumVoltage,
            powerConfig.cutoffVoltage,
            powerConfig.maxTractionCurrentAmps,
            powerConfig.overCurrentThresholdAmps <= 0
                ? powerConfig.maxTractionCurrentAmps * 1.1
                : powerConfig.overCurrentThresholdAmps,
            powerConfig.currentToVoltageDrop,
            powerConfig.regeneration == null || powerConfig.regeneration.sameSectionAbsorbFirst,
            powerConfig.regeneration == null
                ? "BRAKE_RESISTOR"
                : defaultIfBlank(powerConfig.regeneration.unabsorbedMode, "BRAKE_RESISTOR"),
            sections
        );
    }

    private List<OperationalPowerData.PowerSectionDefinition> normalizeCoverage(
        List<OperationalPowerData.PowerSectionDefinition> sections,
        double lineLengthMeters
    ) {
        List<OperationalPowerData.PowerSectionDefinition> normalized = new ArrayList<>(sections);
        OperationalPowerData.PowerSectionDefinition first = normalized.get(0);
        if (first.startMeters() > 0) {
            normalized.set(0, new OperationalPowerData.PowerSectionDefinition(
                first.id(),
                first.name(),
                first.substationId(),
                first.feederId(),
                0,
                first.endMeters(),
                first.substationVoltage(),
                first.energized(),
                first.breakerStatus(),
                first.isolatorStatus(),
                first.supplyMode(),
                first.maintenanceState(),
                first.lockoutState(),
                first.resistanceOhmPerMeter()
            ));
        }

        OperationalPowerData.PowerSectionDefinition last = normalized.get(normalized.size() - 1);
        if (lineLengthMeters > 0 && last.endMeters() < lineLengthMeters) {
            normalized.set(normalized.size() - 1, new OperationalPowerData.PowerSectionDefinition(
                last.id(),
                last.name(),
                last.substationId(),
                last.feederId(),
                last.startMeters(),
                lineLengthMeters,
                last.substationVoltage(),
                last.energized(),
                last.breakerStatus(),
                last.isolatorStatus(),
                last.supplyMode(),
                last.maintenanceState(),
                last.lockoutState(),
                last.resistanceOhmPerMeter()
            ));
        }
        return normalized;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class PowerConfigFile {
        public double nominalVoltage = 1500;
        public double minimumVoltage = 1000;
        public double cutoffVoltage = 900;
        public double maxTractionCurrentAmps = 2000;
        public double overCurrentThresholdAmps;
        public double currentToVoltageDrop = 0.03;
        public Regeneration regeneration;
        public List<PowerSection> sections;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class PowerSection {
        public String id;
        public String name;
        public double startMeters;
        public double endMeters;
        public double substationVoltage = 1500;
        public boolean energized = true;
        public String substationId;
        public String feederId;
        public String breakerStatus;
        public String isolatorStatus;
        public String supplyMode;
        public String maintenanceState;
        public String lockoutState;
        public double resistanceOhmPerMeter;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Regeneration {
        public boolean sameSectionAbsorbFirst = true;
        public String unabsorbedMode = "BRAKE_RESISTOR";
    }
}
