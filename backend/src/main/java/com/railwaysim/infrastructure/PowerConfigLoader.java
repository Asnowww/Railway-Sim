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
                    section.startMeters,
                    section.endMeters,
                    section.substationVoltage,
                    section.energized
                ));
            }
        }

        if (sections.isEmpty()) {
            sections = List.of(new OperationalPowerData.PowerSectionDefinition(
                "P01",
                "全线供电分区",
                0,
                lineLengthMeters,
                powerConfig.nominalVoltage,
                true
            ));
        } else {
            sections = normalizeCoverage(sections, lineLengthMeters);
        }

        return new OperationalPowerData(
            powerConfig.nominalVoltage,
            powerConfig.minimumVoltage,
            powerConfig.cutoffVoltage,
            powerConfig.maxTractionCurrentAmps,
            powerConfig.currentToVoltageDrop,
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
                0,
                first.endMeters(),
                first.substationVoltage(),
                first.energized()
            ));
        }

        OperationalPowerData.PowerSectionDefinition last = normalized.get(normalized.size() - 1);
        if (lineLengthMeters > 0 && last.endMeters() < lineLengthMeters) {
            normalized.set(normalized.size() - 1, new OperationalPowerData.PowerSectionDefinition(
                last.id(),
                last.name(),
                last.startMeters(),
                lineLengthMeters,
                last.substationVoltage(),
                last.energized()
            ));
        }
        return normalized;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class PowerConfigFile {
        public double nominalVoltage = 1500;
        public double minimumVoltage = 1000;
        public double cutoffVoltage = 900;
        public double maxTractionCurrentAmps = 2000;
        public double currentToVoltageDrop = 0.03;
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
    }
}
