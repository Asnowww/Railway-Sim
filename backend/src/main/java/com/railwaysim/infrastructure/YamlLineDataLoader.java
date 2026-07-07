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
public class YamlLineDataLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public OperationalLineData load(Path path) throws IOException {
        YamlLineFile lineFile = yamlMapper.readValue(Files.readString(path), YamlLineFile.class);

        List<OperationalLineData.TrackSegmentDefinition> segments = new ArrayList<>();
        List<OperationalLineData.SpeedLimitZone> speedZones = new ArrayList<>();
        List<OperationalLineData.StationDefinition> stations = new ArrayList<>();
        List<OperationalLineData.SwitchDefinition> switches = new ArrayList<>();

        if (lineFile.segments != null) {
            for (YamlSegment segment : lineFile.segments) {
                String segmentId = segment.id;
                double startMeters = segment.startMeters;
                double endMeters = segment.endMeters;
                double speedLimit = segment.speedLimitMetersPerSecond;
                segments.add(new OperationalLineData.TrackSegmentDefinition(
                    segmentId,
                    parseNumericSuffix(segmentId),
                    startMeters,
                    endMeters,
                    Math.max(0, endMeters - startMeters),
                    speedLimit,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of()
                ));
                speedZones.add(new OperationalLineData.SpeedLimitZone(
                    "LIMIT-" + segmentId,
                    segmentId,
                    startMeters,
                    endMeters,
                    speedLimit,
                    null
                ));
            }
        }

        if (lineFile.stations != null) {
            for (YamlStation station : lineFile.stations) {
                stations.add(new OperationalLineData.StationDefinition(
                    station.id,
                    station.name,
                    station.positionMeters,
                    List.of()
                ));
            }
        }

        if (lineFile.switches != null) {
            for (YamlSwitch yamlSwitch : lineFile.switches) {
                switches.add(new OperationalLineData.SwitchDefinition(
                    yamlSwitch.id,
                    yamlSwitch.id,
                    null,
                    yamlSwitch.defaultPosition,
                    yamlSwitch.normalTarget,
                    yamlSwitch.reverseTarget,
                    yamlSwitch.node,
                    0,
                    null
                ));
            }
        }

        String lineId = lineFile.line == null || lineFile.line.id == null ? "demo-line" : lineFile.line.id;
        String lineName = lineFile.line == null || lineFile.line.name == null ? lineId : lineFile.line.name;

        return new OperationalLineData(
            lineId,
            lineName,
            List.of(),
            segments,
            speedZones,
            List.of(),
            switches,
            stations,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private int parseNumericSuffix(String value) {
        if (value == null) {
            return 0;
        }
        String digits = value.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlLineFile {
        public YamlLine line;
        public List<YamlStation> stations;
        public List<YamlSegment> segments;
        public List<YamlSwitch> switches;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlLine {
        public String id;
        public String name;
        public double lengthMeters;
        public double defaultSpeedLimitMetersPerSecond;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlStation {
        public String id;
        public String name;
        public double positionMeters;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlSegment {
        public String id;
        public String from;
        public String to;
        public double startMeters;
        public double endMeters;
        public double speedLimitMetersPerSecond;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlSwitch {
        public String id;
        public String node;
        public String normalTarget;
        public String reverseTarget;
        public String defaultPosition;
    }
}
