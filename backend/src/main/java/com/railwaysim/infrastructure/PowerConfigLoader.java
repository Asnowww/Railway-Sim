package com.railwaysim.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PowerConfigLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public OperationalPowerData load(Path path, double lineLengthMeters) throws IOException {
        PowerConfigFile powerConfig = yamlMapper.readValue(Files.readString(path), PowerConfigFile.class);

        // ── 全局参数校验 ──
        if (powerConfig.nominalVoltage <= 0) {
            throw new IllegalArgumentException("nominalVoltage must be positive: " + powerConfig.nominalVoltage);
        }
        if (powerConfig.currentToVoltageDrop <= 0) {
            throw new IllegalArgumentException("currentToVoltageDrop must be positive: " + powerConfig.currentToVoltageDrop);
        }

        List<OperationalPowerData.PowerSectionDefinition> sections = new ArrayList<>();
        Set<String> sectionIds = new HashSet<>();
        if (powerConfig.sections != null) {
            for (PowerSection section : powerConfig.sections) {
                if (section.id == null || section.id.isBlank()) {
                    throw new IllegalArgumentException("Power section ID must not be blank");
                }
                if (section.startMeters < 0) {
                    throw new IllegalArgumentException("Power section " + section.id + " startMeters must be >= 0: " + section.startMeters);
                }
                if (section.endMeters <= section.startMeters) {
                    throw new IllegalArgumentException("Power section " + section.id + " endMeters (" + section.endMeters
                        + ") must be > startMeters (" + section.startMeters + ")");
                }
                if (!sectionIds.add(section.id)) {
                    throw new IllegalArgumentException("Duplicate power section ID: " + section.id);
                }
                // Check for overlap with already-added sections
                for (var existing : sections) {
                    if (!existing.id().equals(section.id) && section.startMeters < existing.endMeters() && section.endMeters > existing.startMeters()) {
                        throw new IllegalArgumentException("Power section " + section.id + " [" + section.startMeters + ","
                            + section.endMeters + ") overlaps with " + existing.id() + " [" + existing.startMeters()
                            + "," + existing.endMeters() + ")");
                    }
                }
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
            sections = validateCoverage(sections, lineLengthMeters);
        }

        List<OperationalPowerData.TractionSubstationDefinition> substations = new ArrayList<>();
        if (powerConfig.substations != null) {
            for (TractionSubstation substation : powerConfig.substations) {
                List<OperationalPowerData.SubstationDeviceDefinition> devices = new ArrayList<>();
                if (substation.devices != null) {
                    for (SubstationDevice device : substation.devices) {
                        devices.add(new OperationalPowerData.SubstationDeviceDefinition(
                            device.id,
                            defaultIfBlank(device.name, device.id),
                            defaultIfBlank(device.deviceType, "GENERIC"),
                            defaultIfBlank(device.defaultState, "AVAILABLE"),
                            Math.max(0, device.ratedVoltage),
                            Math.max(0, device.ratedCurrentAmps),
                            device.locationMeters,
                            device.affectsSectionIds == null ? List.of() : List.copyOf(device.affectsSectionIds)
                        ));
                    }
                }
                substations.add(new OperationalPowerData.TractionSubstationDefinition(
                    substation.id,
                    defaultIfBlank(substation.name, substation.id),
                    substation.startMeters,
                    substation.endMeters,
                    defaultIfBlank(substation.supplyMode, "DOUBLE_END"),
                    substation.available,
                    substation.feederIds == null ? List.of() : List.copyOf(substation.feederIds),
                    substation.sectionIds == null ? List.of() : List.copyOf(substation.sectionIds),
                    devices
                ));
            }
        }
        if (substations.isEmpty()) {
            substations = deriveSubstations(sections);
        }

        List<OperationalPowerData.ThirdRailSectionDefinition> thirdRailSections = new ArrayList<>();
        if (powerConfig.thirdRailSections != null) {
            for (ThirdRailSection thirdRailSection : powerConfig.thirdRailSections) {
                thirdRailSections.add(new OperationalPowerData.ThirdRailSectionDefinition(
                    thirdRailSection.id,
                    defaultIfBlank(thirdRailSection.name, thirdRailSection.id),
                    defaultIfBlank(thirdRailSection.powerSectionId, thirdRailSection.id),
                    thirdRailSection.startMeters,
                    thirdRailSection.endMeters,
                    thirdRailSection.isolatorIds == null ? List.of() : List.copyOf(thirdRailSection.isolatorIds),
                    defaultIfBlank(thirdRailSection.returnCurrentDeviceId, "RCD-" + thirdRailSection.id)
                ));
            }
        }
        if (thirdRailSections.isEmpty()) {
            thirdRailSections = deriveThirdRailSections(sections);
        } else {
            validateThirdRailBindings(sections, thirdRailSections);
        }

        List<OperationalPowerData.IsolatorSwitchDefinition> isolators = new ArrayList<>();
        if (powerConfig.isolators != null) {
            for (IsolatorSwitch isolator : powerConfig.isolators) {
                isolators.add(new OperationalPowerData.IsolatorSwitchDefinition(
                    isolator.id,
                    defaultIfBlank(isolator.name, isolator.id),
                    defaultIfBlank(isolator.thirdRailSectionId, isolator.sectionId),
                    isolator.positionMeters,
                    defaultIfBlank(isolator.defaultState, "CLOSED"),
                    isolator.normallyOpen
                ));
            }
        }
        if (isolators.isEmpty()) {
            isolators = deriveIsolators(thirdRailSections);
        }

        List<OperationalPowerData.ReturnCurrentDeviceDefinition> returnCurrentDevices = new ArrayList<>();
        if (powerConfig.returnCurrentDevices != null) {
            for (ReturnCurrentDevice device : powerConfig.returnCurrentDevices) {
                returnCurrentDevices.add(new OperationalPowerData.ReturnCurrentDeviceDefinition(
                    device.id,
                    defaultIfBlank(device.name, device.id),
                    defaultIfBlank(device.sectionId, ""),
                    defaultIfBlank(device.deviceType, "RETURN_CURRENT"),
                    defaultIfBlank(device.defaultState, "NORMAL")
                ));
            }
        }
        if (returnCurrentDevices.isEmpty()) {
            returnCurrentDevices = deriveReturnCurrentDevices(thirdRailSections);
        }

        List<OperationalPowerData.StrayCurrentMonitorPointDefinition> strayCurrentMonitorPoints = new ArrayList<>();
        if (powerConfig.strayCurrentMonitorPoints != null) {
            for (StrayCurrentMonitorPoint point : powerConfig.strayCurrentMonitorPoints) {
                strayCurrentMonitorPoints.add(new OperationalPowerData.StrayCurrentMonitorPointDefinition(
                    point.id,
                    defaultIfBlank(point.name, point.id),
                    defaultIfBlank(point.sectionId, ""),
                    point.positionMeters,
                    defaultIfBlank(point.returnCurrentDeviceId, ""),
                    point.normalMinPotentialVolts,
                    point.normalMaxPotentialVolts == 0 ? 1.8 : point.normalMaxPotentialVolts
                ));
            }
        }
        if (strayCurrentMonitorPoints.isEmpty()) {
            strayCurrentMonitorPoints = deriveStrayCurrentMonitorPoints(thirdRailSections);
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
            sections,
            substations,
            thirdRailSections,
            isolators,
            returnCurrentDevices,
            strayCurrentMonitorPoints
        );
    }

    private List<OperationalPowerData.TractionSubstationDefinition> deriveSubstations(
        List<OperationalPowerData.PowerSectionDefinition> sections
    ) {
        return sections.stream()
            .map(section -> new OperationalPowerData.TractionSubstationDefinition(
                section.substationId(),
                section.substationId(),
                section.startMeters(),
                section.endMeters(),
                section.supplyMode(),
                section.energized(),
                List.of(section.feederId()),
                List.of(section.id()),
                List.of(
                    new OperationalPowerData.SubstationDeviceDefinition(
                        section.substationId() + "-RECTIFIER",
                        section.substationId() + "整流器",
                        "RECTIFIER",
                        section.energized() ? "AVAILABLE" : "OUT_OF_SERVICE",
                        section.substationVoltage(),
                        0,
                        section.startMeters(),
                        List.of(section.id())
                    ),
                    new OperationalPowerData.SubstationDeviceDefinition(
                        section.substationId() + "-DCB",
                        section.substationId() + "直流快速断路器",
                        "DC_BREAKER",
                        defaultIfBlank(section.breakerStatus(), "CLOSED"),
                        section.substationVoltage(),
                        0,
                        section.startMeters(),
                        List.of(section.id())
                    )
                )
            ))
            .toList();
    }

    private List<OperationalPowerData.ThirdRailSectionDefinition> deriveThirdRailSections(
        List<OperationalPowerData.PowerSectionDefinition> sections
    ) {
        return sections.stream()
            .map(section -> new OperationalPowerData.ThirdRailSectionDefinition(
                section.id(),
                section.name(),
                section.id(),
                section.startMeters(),
                section.endMeters(),
                List.of("ISO-" + section.id() + "-A", "ISO-" + section.id() + "-B"),
                "RCD-" + section.id()
            ))
            .toList();
    }

    private List<OperationalPowerData.IsolatorSwitchDefinition> deriveIsolators(
        List<OperationalPowerData.ThirdRailSectionDefinition> thirdRailSections
    ) {
        List<OperationalPowerData.IsolatorSwitchDefinition> derived = new ArrayList<>();
        for (OperationalPowerData.ThirdRailSectionDefinition section : thirdRailSections) {
            derived.add(new OperationalPowerData.IsolatorSwitchDefinition(
                "ISO-" + section.id() + "-A",
                section.name() + "首端隔离开关",
                section.id(),
                section.startMeters(),
                "CLOSED",
                false
            ));
            derived.add(new OperationalPowerData.IsolatorSwitchDefinition(
                "ISO-" + section.id() + "-B",
                section.name() + "末端隔离开关",
                section.id(),
                section.endMeters(),
                "CLOSED",
                false
            ));
        }
        return derived;
    }

    private List<OperationalPowerData.ReturnCurrentDeviceDefinition> deriveReturnCurrentDevices(
        List<OperationalPowerData.ThirdRailSectionDefinition> thirdRailSections
    ) {
        return thirdRailSections.stream()
            .map(section -> new OperationalPowerData.ReturnCurrentDeviceDefinition(
                "RCD-" + section.id(),
                section.name() + "排流柜",
                section.powerSectionId(),
                "DRAINAGE_CABINET",
                "NORMAL"
            ))
            .toList();
    }

    private List<OperationalPowerData.StrayCurrentMonitorPointDefinition> deriveStrayCurrentMonitorPoints(
        List<OperationalPowerData.ThirdRailSectionDefinition> thirdRailSections
    ) {
        return thirdRailSections.stream()
            .map(section -> new OperationalPowerData.StrayCurrentMonitorPointDefinition(
                "SCP-" + section.id(),
                section.name() + "极化电位监测点",
                section.powerSectionId(),
                (section.startMeters() + section.endMeters()) / 2.0,
                "RCD-" + section.id(),
                -0.8,
                1.8
            ))
            .toList();
    }

    private List<OperationalPowerData.PowerSectionDefinition> validateCoverage(
        List<OperationalPowerData.PowerSectionDefinition> sections,
        double lineLengthMeters
    ) {
        List<OperationalPowerData.PowerSectionDefinition> ordered = sections.stream()
            .sorted(Comparator.comparingDouble(OperationalPowerData.PowerSectionDefinition::startMeters))
            .toList();
        if (Math.abs(ordered.get(0).startMeters()) > 1.0e-9) {
            throw new IllegalArgumentException("Power section coverage must start at 0 meters");
        }
        for (int index = 1; index < ordered.size(); index++) {
            double previousEnd = ordered.get(index - 1).endMeters();
            double currentStart = ordered.get(index).startMeters();
            if (Math.abs(previousEnd - currentStart) > 1.0e-9) {
                throw new IllegalArgumentException(
                    "Power section coverage has a gap or overlap between "
                        + ordered.get(index - 1).id() + " and " + ordered.get(index).id()
                        + ": " + previousEnd + " != " + currentStart
                );
            }
        }
        double configuredEnd = ordered.get(ordered.size() - 1).endMeters();
        if (lineLengthMeters > 0 && configuredEnd < lineLengthMeters) {
            throw new IllegalArgumentException(
                "Power section coverage end " + configuredEnd
                    + " does not cover line length " + lineLengthMeters
            );
        }
        return ordered;
    }

    private void validateThirdRailBindings(
        List<OperationalPowerData.PowerSectionDefinition> sections,
        List<OperationalPowerData.ThirdRailSectionDefinition> thirdRails
    ) {
        Set<String> thirdRailIds = new HashSet<>();
        Set<String> boundSectionIds = new HashSet<>();
        for (OperationalPowerData.ThirdRailSectionDefinition thirdRail : thirdRails) {
            if (!thirdRailIds.add(thirdRail.id())) {
                throw new IllegalArgumentException("Duplicate third rail section ID: " + thirdRail.id());
            }
            OperationalPowerData.PowerSectionDefinition section = sections.stream()
                .filter(candidate -> candidate.id().equals(thirdRail.powerSectionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "Third rail section " + thirdRail.id() + " references unknown power section "
                        + thirdRail.powerSectionId()
                ));
            if (!boundSectionIds.add(section.id())) {
                throw new IllegalArgumentException("Multiple third rail sections bind power section " + section.id());
            }
            if (Math.abs(section.startMeters() - thirdRail.startMeters()) > 1.0e-9
                || Math.abs(section.endMeters() - thirdRail.endMeters()) > 1.0e-9) {
                throw new IllegalArgumentException(
                    "Third rail section " + thirdRail.id() + " range does not match " + section.id()
                );
            }
        }
        for (OperationalPowerData.PowerSectionDefinition section : sections) {
            if (!boundSectionIds.contains(section.id())) {
                throw new IllegalArgumentException("Missing third rail binding for power section " + section.id());
            }
        }
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
        public List<TractionSubstation> substations;
        public List<ThirdRailSection> thirdRailSections;
        public List<IsolatorSwitch> isolators;
        public List<ReturnCurrentDevice> returnCurrentDevices;
        public List<StrayCurrentMonitorPoint> strayCurrentMonitorPoints;
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
    static final class TractionSubstation {
        public String id;
        public String name;
        public double startMeters;
        public double endMeters;
        public String supplyMode;
        public boolean available = true;
        public List<String> feederIds;
        public List<String> sectionIds;
        public List<SubstationDevice> devices;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class SubstationDevice {
        public String id;
        public String name;
        public String deviceType;
        public String defaultState;
        public double ratedVoltage;
        public double ratedCurrentAmps;
        public double locationMeters;
        public List<String> affectsSectionIds;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ThirdRailSection {
        public String id;
        public String name;
        public String powerSectionId;
        public double startMeters;
        public double endMeters;
        public List<String> isolatorIds;
        public String returnCurrentDeviceId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class IsolatorSwitch {
        public String id;
        public String name;
        public String thirdRailSectionId;
        public String sectionId;
        public double positionMeters;
        public String defaultState;
        public boolean normallyOpen;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ReturnCurrentDevice {
        public String id;
        public String name;
        public String sectionId;
        public String deviceType;
        public String defaultState;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class StrayCurrentMonitorPoint {
        public String id;
        public String name;
        public String sectionId;
        public double positionMeters;
        public String returnCurrentDeviceId;
        public double normalMinPotentialVolts = -0.8;
        public double normalMaxPotentialVolts = 1.8;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Regeneration {
        public boolean sameSectionAbsorbFirst = true;
        public String unabsorbedMode = "BRAKE_RESISTOR";
    }
}
