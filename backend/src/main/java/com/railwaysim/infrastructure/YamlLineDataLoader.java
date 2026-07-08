package com.railwaysim.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 从 YAML 加载线路数据，自动计算区段拓扑邻居关系。
 *
 * <p>邻居计算规则：
 * <ul>
 *   <li>forwardNeighbor: other.from == this.to（前方衔接）</li>
 *   <li>sideNeighbor: other.from == this.from && other.to != this.to（同节点分叉）</li>
 * </ul>
 *
 * <p>YAML 中 segments 的 {@code from}/{@code to} 字段为节点 ID，对应 nodes 表。
 */
@Component
public class YamlLineDataLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public OperationalLineData load(Path path) throws IOException {
        YamlLineFile lineFile = yamlMapper.readValue(Files.readString(path), YamlLineFile.class);

        // ---- 1. 收集节点信息 ----
        List<OperationalLineData.PointDefinition> points = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        if (lineFile.nodes != null) {
            for (YamlNode node : lineFile.nodes) {
                points.add(new OperationalLineData.PointDefinition(
                    node.id, node.name, null, 0, null
                ));
                nodeIds.add(node.id);
            }
        }

        // ---- 2. 解析区段中间数据 ----
        record RawSeg(String id, String from, String to, double start, double end, double speed, String track) {}
        List<RawSeg> raws = new ArrayList<>();
        if (lineFile.segments != null) {
            for (YamlSegment seg : lineFile.segments) {
                raws.add(new RawSeg(
                    seg.id, seg.from, seg.to,
                    seg.startMeters, seg.endMeters,
                    seg.speedLimitMetersPerSecond,
                    seg.track != null ? seg.track : "main"
                ));
                nodeIds.add(seg.from);
                nodeIds.add(seg.to);
            }
        }

        // ---- 3. 计算邻居关系 ----
        Map<String, List<String>> forwardMap = new HashMap<>();
        Map<String, List<String>> sideMap = new HashMap<>();
        for (RawSeg seg : raws) {
            List<String> forward = raws.stream()
                .filter(o -> !o.id.equals(seg.id) && seg.to.equals(o.from))
                .map(o -> o.id)
                .toList();
            forwardMap.put(seg.id, forward);

            List<String> side = raws.stream()
                .filter(o -> !o.id.equals(seg.id) && seg.from.equals(o.from) && !seg.to.equals(o.to))
                .map(o -> o.id)
                .toList();
            sideMap.put(seg.id, side);
        }

        // ---- 4. 构建 TrackSegmentDefinition ----
        List<OperationalLineData.TrackSegmentDefinition> segments = new ArrayList<>();
        List<OperationalLineData.SpeedLimitZone> speedZones = new ArrayList<>();
        for (RawSeg raw : raws) {
            segments.add(new OperationalLineData.TrackSegmentDefinition(
                raw.id,
                parseNumericSuffix(raw.id),
                raw.start,
                raw.end,
                Math.max(0, raw.end - raw.start),
                raw.speed,
                0, 0, 0, 0,
                forwardMap.getOrDefault(raw.id, List.of()),
                sideMap.getOrDefault(raw.id, List.of()),
                raw.from,
                raw.to,
                raw.track
            ));
            speedZones.add(new OperationalLineData.SpeedLimitZone(
                "LIMIT-" + raw.id, raw.id,
                raw.start, raw.end, raw.speed, null
            ));
        }

        // ---- 5. 补齐未出现但被引用的节点 ----
        for (String nodeId : nodeIds) {
            boolean listed = points.stream().anyMatch(p -> p.id().equals(nodeId));
            if (!listed) {
                points.add(new OperationalLineData.PointDefinition(
                    nodeId, nodeId, null, 0, null
                ));
            }
        }

        // ---- 6. 进路（联锁需要） ----
        List<OperationalLineData.RouteDefinition> routes = new ArrayList<>();
        if (lineFile.routes != null) {
            for (YamlRoute route : lineFile.routes) {
                routes.add(new OperationalLineData.RouteDefinition(
                    route.id, route.name, route.type != null ? route.type : "MAIN",
                    route.startSignal, route.endSignal,
                    route.axleSectionIds != null ? List.copyOf(route.axleSectionIds) : List.of(),
                    route.protectionSectionIds != null ? List.copyOf(route.protectionSectionIds) : List.of(),
                    List.of(), List.of(), List.of(), List.of()
                ));
            }
        }

        // ---- 7. 车站 & 道岔 ----
        List<OperationalLineData.StationDefinition> stations = new ArrayList<>();
        if (lineFile.stations != null) {
            for (YamlStation st : lineFile.stations) {
                stations.add(new OperationalLineData.StationDefinition(
                    st.id, st.name, st.positionMeters, List.of()
                ));
            }
        }

        List<OperationalLineData.SwitchDefinition> switches = new ArrayList<>();
        if (lineFile.switches != null) {
            for (YamlSwitch sw : lineFile.switches) {
                switches.add(new OperationalLineData.SwitchDefinition(
                    sw.id, sw.id, null,
                    sw.defaultPosition != null ? sw.defaultPosition : "NORMAL",
                    sw.normalTarget, sw.reverseTarget, sw.node, 0, null,
                    sw.defaultPosition != null ? sw.defaultPosition : "NORMAL"
                ));
            }
        }

        // ---- 8. 组装 ----
        String lineId = lineFile.line == null || lineFile.line.id == null ? "demo-line" : lineFile.line.id;
        String lineName = lineFile.line == null || lineFile.line.name == null ? lineId : lineFile.line.name;

        return new OperationalLineData(
            lineId, lineName,
            points, segments, speedZones, List.of(),
            switches, stations, List.of(), List.of(), List.of(),
            routes
        );
    }

    private int parseNumericSuffix(String value) {
        if (value == null) return 0;
        String digits = value.replaceAll("\\D+", "");
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    // ---- YAML 映射类 ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlLineFile {
        public YamlLine line;
        public List<YamlNode> nodes;
        public List<YamlStation> stations;
        public List<YamlSegment> segments;
        public List<YamlRoute> routes;
        public List<YamlSwitch> switches;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlLine {
        public String id;
        public String name;
        @JsonProperty("length_meters") public double lengthMeters;
        @JsonProperty("default_speed_limit_meters_per_second") public double defaultSpeedLimitMetersPerSecond;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlNode {
        public String id;
        public String name;
        public String type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlStation {
        public String id;
        public String name;
        @JsonProperty("position_meters") public double positionMeters;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlSegment {
        public String id;
        public String from;
        public String to;
        @JsonProperty("start_meters") public double startMeters;
        @JsonProperty("end_meters") public double endMeters;
        @JsonProperty("speed_limit_meters_per_second") public double speedLimitMetersPerSecond;
        public String track;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlRoute {
        public String id;
        public String name;
        public String type;
        @JsonProperty("start_signal") public String startSignal;
        @JsonProperty("end_signal") public String endSignal;
        @JsonProperty("axle_section_ids") public List<String> axleSectionIds;
        @JsonProperty("protection_section_ids") public List<String> protectionSectionIds;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlSwitch {
        public String id;
        public String node;
        @JsonProperty("normal_target") public String normalTarget;
        @JsonProperty("reverse_target") public String reverseTarget;
        @JsonProperty("default_position") public String defaultPosition;
    }
}
