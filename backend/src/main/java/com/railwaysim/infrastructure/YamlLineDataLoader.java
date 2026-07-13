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
        record RawSeg(String id, String from, String to, double start, double end, double speed, String track, int rawId) {}
        List<RawSeg> raws = new ArrayList<>();
        if (lineFile.segments != null) {
            for (YamlSegment seg : lineFile.segments) {
                raws.add(new RawSeg(
                    seg.id, seg.from, seg.to,
                    seg.startMeters, seg.endMeters,
                    seg.speedLimitMetersPerSecond,
                    seg.track != null ? seg.track : "main",
                    // 视景边号：yaml 显式给出优先（视景 UDP segNo），否则回退区段 ID 数字后缀
                    seg.rawSegmentId != null ? seg.rawSegmentId : parseNumericSuffix(seg.id)
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
                raw.rawId,
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

        // ---- 7. 车站（含可选双向站台）& 道岔 ----
        List<OperationalLineData.StationDefinition> stations = new ArrayList<>();
        List<OperationalLineData.PlatformDefinition> platforms = new ArrayList<>();
        if (lineFile.stations != null) {
            for (YamlStation st : lineFile.stations) {
                List<String> platformIds = new ArrayList<>();
                if (st.platforms != null) {
                    for (YamlPlatform pl : st.platforms) {
                        String track = pl.track != null ? pl.track : "main";
                        String platformId = st.id + "-" + track.toUpperCase(java.util.Locale.ROOT);
                        // anchorSegmentId：站台中心落在该股道的哪个区段
                        String anchor = segments.stream()
                            .filter(s -> track.equals(s.track())
                                && pl.centerMeters >= s.startMeters() && pl.centerMeters <= s.endMeters())
                            .map(OperationalLineData.TrackSegmentDefinition::id)
                            .findFirst()
                            .orElse(null);
                        platforms.add(new OperationalLineData.PlatformDefinition(
                            platformId,
                            pl.centerMeters,
                            anchor,
                            track.toUpperCase(java.util.Locale.ROOT),
                            null,
                            null,
                            pl.stopLeftMeters,
                            pl.stopRightMeters,
                            pl.side
                        ));
                        platformIds.add(platformId);
                    }
                }
                stations.add(new OperationalLineData.StationDefinition(
                    st.id, st.name, st.positionMeters, platformIds
                ));
            }
        }

        List<OperationalLineData.SwitchDefinition> switches = new ArrayList<>();
        if (lineFile.switches != null) {
            for (YamlSwitch sw : lineFile.switches) {
                // TODO: directionCode 当前用 defaultPosition 占位，YAML 不区分方向编码
                switches.add(new OperationalLineData.SwitchDefinition(
                    sw.id, sw.id, null,
                    sw.defaultPosition != null ? sw.defaultPosition : "NORMAL",
                    sw.normalTarget, sw.reverseTarget, sw.node, 0, null,
                    sw.defaultPosition != null ? sw.defaultPosition : "NORMAL"
                ));
            }
        }

        // ---- 8. 信号机 ----
        List<OperationalLineData.SignalDefinition> signals = new ArrayList<>();
        if (lineFile.signals != null) {
            for (YamlSignal sig : lineFile.signals) {
                String direction = sig.direction != null ? sig.direction : "FORWARD";
                signals.add(new OperationalLineData.SignalDefinition(
                    sig.id, sig.name != null ? sig.name : sig.id,
                    null, null, sig.segmentId,
                    sig.positionMeters, direction, null, null
                ));
            }
        }

        validateRoutes(routes, segments, switches, signals);

        // ---- 9. 组装 ----
        String lineId = lineFile.line == null || lineFile.line.id == null ? "demo-line" : lineFile.line.id;
        String lineName = lineFile.line == null || lineFile.line.name == null ? lineId : lineFile.line.name;

        return new OperationalLineData(
            lineId, lineName,
            points, segments, speedZones, List.of(),
            switches, stations, platforms, signals, List.of(),
            routes
        );
    }

    private int parseNumericSuffix(String value) {
        if (value == null) return 0;
        String digits = value.replaceAll("\\D+", "");
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    private void validateRoutes(
        List<OperationalLineData.RouteDefinition> routes,
        List<OperationalLineData.TrackSegmentDefinition> segments,
        List<OperationalLineData.SwitchDefinition> switches,
        List<OperationalLineData.SignalDefinition> signals
    ) throws IOException {
        Map<String, OperationalLineData.TrackSegmentDefinition> segmentById = segments.stream()
            .collect(java.util.stream.Collectors.toMap(OperationalLineData.TrackSegmentDefinition::id, segment -> segment));
        Map<String, OperationalLineData.SignalDefinition> signalById = signals.stream()
            .collect(java.util.stream.Collectors.toMap(OperationalLineData.SignalDefinition::id, signal -> signal));
        for (OperationalLineData.RouteDefinition route : routes) {
            List<String> path = route.axleSectionIds();
            if (path.isEmpty()) {
                throw new IOException("Route " + route.id() + " has no axle sections");
            }
            for (String segmentId : path) {
                if (!segmentById.containsKey(segmentId)) {
                    throw new IOException("Route " + route.id() + " references unknown segment " + segmentId);
                }
            }
            for (int i = 1; i < path.size(); i++) {
                OperationalLineData.TrackSegmentDefinition previous = segmentById.get(path.get(i - 1));
                OperationalLineData.TrackSegmentDefinition current = segmentById.get(path.get(i));
                if (!previous.toNodeId().equals(current.fromNodeId())) {
                    throw new IOException("Route " + route.id() + " is disconnected between "
                        + previous.id() + " and " + current.id());
                }
            }
            for (OperationalLineData.SwitchDefinition trackSwitch : switches) {
                if (path.contains(trackSwitch.normalSegmentId()) && path.contains(trackSwitch.reverseSegmentId())) {
                    throw new IOException("Route " + route.id() + " requires switch " + trackSwitch.id()
                        + " in both NORMAL and REVERSE");
                }
            }
            validateRouteSignal(route.id(), "start", route.startSignalId(), path.get(0), signalById);
            validateRouteSignal(route.id(), "end", route.endSignalId(), path.get(path.size() - 1), signalById);
        }
    }

    private void validateRouteSignal(String routeId, String role, String signalId, String expectedSegmentId,
        Map<String, OperationalLineData.SignalDefinition> signalById) throws IOException {
        OperationalLineData.SignalDefinition signal = signalById.get(signalId);
        if (signal == null || !expectedSegmentId.equals(signal.segmentId())) {
            throw new IOException("Route " + routeId + " " + role + " signal " + signalId
                + " must belong to segment " + expectedSegmentId);
        }
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
        public List<YamlSignal> signals;
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
        /** 可选：双向站台（9号线上下行站中心/停车窗口不同） */
        public List<YamlPlatform> platforms;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlPlatform {
        public String track;
        @JsonProperty("center_meters") public double centerMeters;
        @JsonProperty("stop_left_meters") public double stopLeftMeters;
        @JsonProperty("stop_right_meters") public double stopRightMeters;
        public String side;
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
        /** 可选：视景系统边号（UDP segNo）；缺省回退区段 ID 数字后缀 */
        @JsonProperty("raw_segment_id") public Integer rawSegmentId;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class YamlSignal {
        public String id;
        public String name;
        @JsonProperty("position_meters") public double positionMeters;
        @JsonProperty("direction") public String direction;
        @JsonProperty("segment_id") public String segmentId;
    }
}
