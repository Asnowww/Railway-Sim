package com.railwaysim.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcLineDataLoader {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcLineDataLoader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OperationalLineData load(String lineId) {
        LineRow line = loadLine(lineId);
        List<OperationalLineData.PointDefinition> points = loadPoints(line.lineId());
        List<OperationalLineData.TrackSegmentDefinition> trackSegments = loadTrackSegments(line.lineId());
        List<OperationalLineData.SpeedLimitZone> speedLimitZones = loadSpeedLimitZones(line.lineId());
        List<OperationalLineData.GradientZone> gradientZones = loadGradientZones(line.lineId());
        List<OperationalLineData.PlatformDefinition> platforms = loadPlatforms(line.lineId());
        Map<String, List<String>> platformIdsByStation = loadStationPlatformIds(line.lineId());
        List<OperationalLineData.StationDefinition> stations = loadStations(line.lineId(), platformIdsByStation);
        List<OperationalLineData.SwitchDefinition> switches = loadSwitches(line.lineId());
        List<OperationalLineData.SignalDefinition> signals = loadSignals(line.lineId());
        List<OperationalLineData.BaliseDefinition> balises = loadBalises(line.lineId());
        List<OperationalLineData.RouteDefinition> routes = loadRoutes(line.lineId());

        return new OperationalLineData(
            line.lineId(),
            line.lineName(),
            points,
            trackSegments,
            speedLimitZones,
            gradientZones,
            switches,
            stations,
            platforms,
            signals,
            balises,
            routes
        );
    }

    private LineRow loadLine(String lineId) {
        try {
            return jdbcTemplate.queryForObject(
                """
                    SELECT line_id, line_name, length_meters, default_speed_limit_mps
                    FROM line_config
                    WHERE line_id = ? AND enabled = TRUE
                    """,
                (rs, rowNum) -> new LineRow(
                    rs.getString("line_id"),
                    rs.getString("line_name"),
                    rs.getDouble("length_meters"),
                    rs.getDouble("default_speed_limit_mps")
                ),
                lineId
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException("No enabled line_config found for line_id=" + lineId, ex);
        }
    }

    private List<OperationalLineData.PointDefinition> loadPoints(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT point_id, point_name, track_name, kilometer_mark_meters, direction_code
                FROM point_config
                WHERE line_id = ?
                ORDER BY kilometer_mark_meters, point_id
                """,
            (rs, rowNum) -> new OperationalLineData.PointDefinition(
                rs.getString("point_id"),
                rs.getString("point_name"),
                rs.getString("track_name"),
                rs.getDouble("kilometer_mark_meters"),
                rs.getString("direction_code")
            ),
            lineId
        );
    }

    private List<OperationalLineData.TrackSegmentDefinition> loadTrackSegments(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT s.segment_id, s.start_meters, s.end_meters, s.speed_limit_mps,
                       t.raw_segment_id, t.length_meters, t.start_endpoint_type,
                       t.start_endpoint_id, t.end_endpoint_type, t.end_endpoint_id,
                       t.forward_neighbor_ids_json, t.side_neighbor_ids_json
                FROM track_segment_config s
                LEFT JOIN track_segment_topology_config t ON t.segment_id = s.segment_id
                WHERE s.line_id = ?
                ORDER BY s.start_meters, s.segment_id
                """,
            (rs, rowNum) -> {
                double startMeters = rs.getDouble("start_meters");
                double endMeters = rs.getDouble("end_meters");
                double lengthMeters = rs.getDouble("length_meters");
                if (rs.wasNull() || lengthMeters <= 0) {
                    lengthMeters = Math.max(0, endMeters - startMeters);
                }
                return new OperationalLineData.TrackSegmentDefinition(
                    rs.getString("segment_id"),
                    rs.getInt("raw_segment_id"),
                    startMeters,
                    endMeters,
                    lengthMeters,
                    rs.getDouble("speed_limit_mps"),
                    rs.getInt("start_endpoint_type"),
                    rs.getInt("start_endpoint_id"),
                    rs.getInt("end_endpoint_type"),
                    rs.getInt("end_endpoint_id"),
                    parseList(rs.getString("forward_neighbor_ids_json")),
                    parseList(rs.getString("side_neighbor_ids_json")),
                "", "", "main"
                );
            },
            lineId
        );
    }

    private List<OperationalLineData.SpeedLimitZone> loadSpeedLimitZones(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT zone_id, segment_id, start_meters, end_meters, speed_limit_mps, switch_id
                FROM speed_limit_zone_config
                WHERE line_id = ?
                ORDER BY start_meters, end_meters, zone_id
                """,
            (rs, rowNum) -> new OperationalLineData.SpeedLimitZone(
                rs.getString("zone_id"),
                rs.getString("segment_id"),
                rs.getDouble("start_meters"),
                rs.getDouble("end_meters"),
                rs.getDouble("speed_limit_mps"),
                rs.getString("switch_id")
            ),
            lineId
        );
    }

    private List<OperationalLineData.GradientZone> loadGradientZones(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT zone_id, start_meters, end_meters, gradient, raw_permille_value, direction_code
                FROM gradient_zone_config
                WHERE line_id = ?
                ORDER BY start_meters, end_meters, zone_id
                """,
            (rs, rowNum) -> new OperationalLineData.GradientZone(
                rs.getString("zone_id"),
                rs.getDouble("start_meters"),
                rs.getDouble("end_meters"),
                rs.getDouble("gradient"),
                rs.getInt("raw_permille_value"),
                rs.getString("direction_code")
            ),
            lineId
        );
    }

    private List<OperationalLineData.PlatformDefinition> loadPlatforms(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT platform_id, center_meters, anchor_segment_id, direction_code,
                       raw_center_mark, interoperability_id
                FROM platform_config
                WHERE line_id = ?
                ORDER BY center_meters, platform_id
                """,
            (rs, rowNum) -> new OperationalLineData.PlatformDefinition(
                rs.getString("platform_id"),
                rs.getDouble("center_meters"),
                rs.getString("anchor_segment_id"),
                rs.getString("direction_code"),
                rs.getString("raw_center_mark"),
                rs.getString("interoperability_id")
            ),
            lineId
        );
    }

    private Map<String, List<String>> loadStationPlatformIds(String lineId) {
        return jdbcTemplate.query(
                """
                    SELECT station_id, platform_id
                    FROM station_platform_config
                    WHERE line_id = ?
                    ORDER BY station_id, platform_id
                    """,
                (rs, rowNum) -> new StationPlatformRow(
                    rs.getString("station_id"),
                    rs.getString("platform_id")
                ),
                lineId
            )
            .stream()
            .collect(Collectors.groupingBy(
                StationPlatformRow::stationId,
                Collectors.mapping(StationPlatformRow::platformId, Collectors.toList())
            ));
    }

    private List<OperationalLineData.StationDefinition> loadStations(
        String lineId,
        Map<String, List<String>> platformIdsByStation
    ) {
        return jdbcTemplate.query(
            """
                SELECT station_id, station_name, position_meters
                FROM station_config
                WHERE line_id = ?
                ORDER BY position_meters, station_id
                """,
            (rs, rowNum) -> {
                String stationId = rs.getString("station_id");
                return new OperationalLineData.StationDefinition(
                    stationId,
                    rs.getString("station_name"),
                    rs.getDouble("position_meters"),
                    platformIdsByStation.getOrDefault(stationId, List.of())
                );
            },
            lineId
        );
    }

    private List<OperationalLineData.SwitchDefinition> loadSwitches(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT s.switch_id, s.normal_target, s.reverse_target, s.default_position,
                       d.switch_name, d.linked_switch_id, d.direction_code, d.merge_segment_id,
                       d.diverging_speed_limit_mps, d.interoperability_id
                FROM switch_config s
                LEFT JOIN switch_detail_config d ON d.switch_id = s.switch_id
                WHERE s.line_id = ?
                ORDER BY s.switch_id
                """,
            (rs, rowNum) -> new OperationalLineData.SwitchDefinition(
                rs.getString("switch_id"),
                valueOrDefault(rs.getString("switch_name"), rs.getString("switch_id")),
                rs.getString("linked_switch_id"),
                valueOrDefault(rs.getString("direction_code"), rs.getString("default_position")),
                rs.getString("normal_target"),
                rs.getString("reverse_target"),
                rs.getString("merge_segment_id"),
                rs.getDouble("diverging_speed_limit_mps"),
                rs.getString("interoperability_id")
            ),
            lineId
        );
    }

    private List<OperationalLineData.SignalDefinition> loadSignals(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT signal_id, signal_name, type_code, attribute_code, segment_id,
                       position_meters, protection_direction_code, lamp_info_code, interoperability_id
                FROM signal_config
                WHERE line_id = ?
                ORDER BY position_meters, signal_id
                """,
            (rs, rowNum) -> new OperationalLineData.SignalDefinition(
                rs.getString("signal_id"),
                rs.getString("signal_name"),
                rs.getString("type_code"),
                rs.getString("attribute_code"),
                rs.getString("segment_id"),
                rs.getDouble("position_meters"),
                rs.getString("protection_direction_code"),
                rs.getString("lamp_info_code"),
                rs.getString("interoperability_id")
            ),
            lineId
        );
    }

    private List<OperationalLineData.BaliseDefinition> loadBalises(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT balise_id, hex_id, balise_name, segment_id, position_meters,
                       interoperability_id, attribute_code, linked_signal_id, direction_code
                FROM balise_config
                WHERE line_id = ?
                ORDER BY position_meters, balise_id
                """,
            (rs, rowNum) -> new OperationalLineData.BaliseDefinition(
                rs.getString("balise_id"),
                rs.getString("hex_id"),
                rs.getString("balise_name"),
                rs.getString("segment_id"),
                rs.getDouble("position_meters"),
                rs.getString("interoperability_id"),
                rs.getString("attribute_code"),
                rs.getString("linked_signal_id"),
                rs.getString("direction_code")
            ),
            lineId
        );
    }

    private List<OperationalLineData.RouteDefinition> loadRoutes(String lineId) {
        return jdbcTemplate.query(
            """
                SELECT route_id, route_name, type_code, start_signal_id, end_signal_id,
                       axle_section_ids_json, protection_section_ids_json,
                       point_approach_section_ids_json, cbtc_approach_section_ids_json,
                       point_trigger_section_ids_json, cbtc_trigger_section_ids_json
                FROM route_config
                WHERE line_id = ?
                ORDER BY route_id
                """,
            (rs, rowNum) -> new OperationalLineData.RouteDefinition(
                rs.getString("route_id"),
                rs.getString("route_name"),
                rs.getString("type_code"),
                rs.getString("start_signal_id"),
                rs.getString("end_signal_id"),
                parseList(rs.getString("axle_section_ids_json")),
                parseList(rs.getString("protection_section_ids_json")),
                parseList(rs.getString("point_approach_section_ids_json")),
                parseList(rs.getString("cbtc_approach_section_ids_json")),
                parseList(rs.getString("point_trigger_section_ids_json")),
                parseList(rs.getString("cbtc_trigger_section_ids_json"))
            ),
            lineId
        );
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String normalized = json.trim();
        try {
            if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
                normalized = objectMapper.readValue(normalized, String.class);
            }
            return objectMapper.readValue(normalized, STRING_LIST);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse line data JSON list", ex);
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record LineRow(
        String lineId,
        String lineName,
        double lengthMeters,
        double defaultSpeedLimitMetersPerSecond
    ) {
    }

    private record StationPlatformRow(String stationId, String platformId) {
    }
}
