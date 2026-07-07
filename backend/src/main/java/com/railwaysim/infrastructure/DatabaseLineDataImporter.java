package com.railwaysim.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseLineDataImporter {

    private final JdbcTemplate jdbcTemplate;
    private final SpreadsheetLineDataLoader spreadsheetLineDataLoader;
    private final ObjectMapper objectMapper;

    public DatabaseLineDataImporter(
        JdbcTemplate jdbcTemplate,
        SpreadsheetLineDataLoader spreadsheetLineDataLoader,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.spreadsheetLineDataLoader = spreadsheetLineDataLoader;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OperationalLineData importWorkbook(Path workbookPath, double defaultSpeedLimitMetersPerSecond) throws IOException {
        OperationalLineData lineData = spreadsheetLineDataLoader.load(workbookPath, defaultSpeedLimitMetersPerSecond);
        replaceLineData(lineData, defaultSpeedLimitMetersPerSecond);
        return lineData;
    }

    @Transactional
    public void replaceLineData(OperationalLineData lineData, double defaultSpeedLimitMetersPerSecond) {
        deleteLineData(lineData.lineId());
        insertLine(lineData, defaultSpeedLimitMetersPerSecond);
        insertPoints(lineData);
        insertTrackSegments(lineData);
        insertSpeedLimits(lineData);
        insertGradients(lineData);
        insertPlatforms(lineData);
        insertStations(lineData);
        insertSwitches(lineData);
        insertSignals(lineData);
        insertBalises(lineData);
        insertRoutes(lineData);
    }

    public boolean hasLine(String lineId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM line_config WHERE line_id = ? AND enabled = TRUE",
            Integer.class,
            lineId
        );
        return count != null && count > 0;
    }

    private void deleteLineData(String lineId) {
        for (String table : List.of(
            "route_config",
            "balise_config",
            "signal_config",
            "switch_detail_config",
            "station_platform_config",
            "platform_config",
            "gradient_zone_config",
            "speed_limit_zone_config",
            "track_segment_topology_config",
            "point_config",
            "switch_config",
            "track_segment_config",
            "station_config",
            "line_config"
        )) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE line_id = ?", lineId);
        }
    }

    private void insertLine(OperationalLineData lineData, double defaultSpeedLimitMetersPerSecond) {
        jdbcTemplate.update(
            """
                INSERT INTO line_config (line_id, line_name, length_meters, default_speed_limit_mps, enabled)
                VALUES (?, ?, ?, ?, TRUE)
                """,
            lineData.lineId(),
            lineData.lineName(),
            lineData.lineLengthMeters(),
            defaultSpeedLimitMetersPerSecond
        );
    }

    private void insertPoints(OperationalLineData lineData) {
        for (OperationalLineData.PointDefinition point : lineData.points()) {
            jdbcTemplate.update(
                """
                    INSERT INTO point_config (
                      point_id, line_id, point_name, track_name, kilometer_mark_meters, direction_code
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                point.id(),
                lineData.lineId(),
                point.name(),
                point.trackName(),
                point.kilometerMarkMeters(),
                point.directionCode()
            );
        }
    }

    private void insertTrackSegments(OperationalLineData lineData) {
        for (OperationalLineData.TrackSegmentDefinition segment : lineData.trackSegments()) {
            jdbcTemplate.update(
                """
                    INSERT INTO track_segment_config (
                      segment_id, line_id, from_node, to_node, start_meters, end_meters,
                      speed_limit_mps, gradient_permille
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                segment.id(),
                lineData.lineId(),
                endpoint(segment.startEndpointType(), segment.startEndpointId()),
                endpoint(segment.endEndpointType(), segment.endEndpointId()),
                segment.startMeters(),
                segment.endMeters(),
                segment.defaultSpeedLimitMetersPerSecond(),
                lineData.gradientAt(segment.startMeters()) * 1000.0
            );
            jdbcTemplate.update(
                """
                    INSERT INTO track_segment_topology_config (
                      segment_id, line_id, raw_segment_id, length_meters, start_endpoint_type,
                      start_endpoint_id, end_endpoint_type, end_endpoint_id,
                      forward_neighbor_ids_json, side_neighbor_ids_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                segment.id(),
                lineData.lineId(),
                segment.rawSegmentId(),
                segment.lengthMeters(),
                segment.startEndpointType(),
                segment.startEndpointId(),
                segment.endEndpointType(),
                segment.endEndpointId(),
                toJson(segment.forwardNeighborSegmentIds()),
                toJson(segment.sideNeighborSegmentIds())
            );
        }
    }

    private void insertSpeedLimits(OperationalLineData lineData) {
        for (OperationalLineData.SpeedLimitZone zone : lineData.speedLimitZones()) {
            jdbcTemplate.update(
                """
                    INSERT INTO speed_limit_zone_config (
                      zone_id, line_id, segment_id, start_meters, end_meters, speed_limit_mps, switch_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                zone.id(),
                lineData.lineId(),
                zone.segmentId(),
                zone.startMeters(),
                zone.endMeters(),
                zone.speedLimitMetersPerSecond(),
                zone.switchId()
            );
        }
    }

    private void insertGradients(OperationalLineData lineData) {
        for (OperationalLineData.GradientZone zone : lineData.gradientZones()) {
            jdbcTemplate.update(
                """
                    INSERT INTO gradient_zone_config (
                      zone_id, line_id, start_meters, end_meters, gradient, raw_permille_value, direction_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                zone.id(),
                lineData.lineId(),
                zone.startMeters(),
                zone.endMeters(),
                zone.gradient(),
                zone.rawPermilleValue(),
                zone.directionCode()
            );
        }
    }

    private void insertPlatforms(OperationalLineData lineData) {
        for (OperationalLineData.PlatformDefinition platform : lineData.platforms()) {
            jdbcTemplate.update(
                """
                    INSERT INTO platform_config (
                      platform_id, line_id, center_meters, anchor_segment_id, direction_code,
                      raw_center_mark, interoperability_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                platform.id(),
                lineData.lineId(),
                platform.centerMeters(),
                platform.anchorSegmentId(),
                platform.directionCode(),
                platform.rawCenterMark(),
                platform.interoperabilityId()
            );
        }
    }

    private void insertStations(OperationalLineData lineData) {
        for (OperationalLineData.StationDefinition station : lineData.stations()) {
            jdbcTemplate.update(
                """
                    INSERT INTO station_config (
                      station_id, line_id, station_name, position_meters, platform_capacity
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                station.id(),
                lineData.lineId(),
                station.name(),
                station.centerMeters(),
                station.platformIds().size()
            );
            for (String platformId : station.platformIds()) {
                jdbcTemplate.update(
                    """
                        INSERT INTO station_platform_config (station_id, platform_id, line_id)
                        VALUES (?, ?, ?)
                        """,
                    station.id(),
                    platformId,
                    lineData.lineId()
                );
            }
        }
    }

    private void insertSwitches(OperationalLineData lineData) {
        for (OperationalLineData.SwitchDefinition switchDefinition : lineData.switches()) {
            jdbcTemplate.update(
                """
                    INSERT INTO switch_config (
                      switch_id, line_id, node_id, normal_target, reverse_target, default_position
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                switchDefinition.id(),
                lineData.lineId(),
                valueOrDefault(switchDefinition.mergeSegmentId(), switchDefinition.id()),
                valueOrDefault(switchDefinition.normalSegmentId(), ""),
                valueOrDefault(switchDefinition.reverseSegmentId(), ""),
                valueOrDefault(switchDefinition.directionCode(), "NORMAL")
            );
            jdbcTemplate.update(
                """
                    INSERT INTO switch_detail_config (
                      switch_id, line_id, switch_name, linked_switch_id, direction_code,
                      merge_segment_id, diverging_speed_limit_mps, interoperability_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                switchDefinition.id(),
                lineData.lineId(),
                switchDefinition.name(),
                switchDefinition.linkedSwitchId(),
                switchDefinition.directionCode(),
                switchDefinition.mergeSegmentId(),
                switchDefinition.divergingSpeedLimitMetersPerSecond(),
                switchDefinition.interoperabilityId()
            );
        }
    }

    private void insertSignals(OperationalLineData lineData) {
        for (OperationalLineData.SignalDefinition signal : lineData.signals()) {
            jdbcTemplate.update(
                """
                    INSERT INTO signal_config (
                      signal_id, line_id, signal_name, type_code, attribute_code, segment_id,
                      position_meters, protection_direction_code, lamp_info_code, interoperability_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                signal.id(),
                lineData.lineId(),
                signal.name(),
                signal.typeCode(),
                signal.attributeCode(),
                signal.segmentId(),
                signal.positionMeters(),
                signal.protectionDirectionCode(),
                signal.lampInfoCode(),
                signal.interoperabilityId()
            );
        }
    }

    private void insertBalises(OperationalLineData lineData) {
        for (OperationalLineData.BaliseDefinition balise : lineData.balises()) {
            jdbcTemplate.update(
                """
                    INSERT INTO balise_config (
                      balise_id, line_id, hex_id, balise_name, segment_id, position_meters,
                      interoperability_id, attribute_code, linked_signal_id, direction_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                balise.id(),
                lineData.lineId(),
                balise.hexId(),
                balise.name(),
                balise.segmentId(),
                balise.positionMeters(),
                balise.interoperabilityId(),
                balise.attributeCode(),
                balise.linkedSignalId(),
                balise.directionCode()
            );
        }
    }

    private void insertRoutes(OperationalLineData lineData) {
        for (OperationalLineData.RouteDefinition route : lineData.routes()) {
            jdbcTemplate.update(
                """
                    INSERT INTO route_config (
                      route_id, line_id, route_name, type_code, start_signal_id, end_signal_id,
                      axle_section_ids_json, protection_section_ids_json,
                      point_approach_section_ids_json, cbtc_approach_section_ids_json,
                      point_trigger_section_ids_json, cbtc_trigger_section_ids_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                route.id(),
                lineData.lineId(),
                route.name(),
                route.typeCode(),
                route.startSignalId(),
                route.endSignalId(),
                toJson(route.axleSectionIds()),
                toJson(route.protectionSectionIds()),
                toJson(route.pointApproachSectionIds()),
                toJson(route.cbtcApproachSectionIds()),
                toJson(route.pointTriggerSectionIds()),
                toJson(route.cbtcTriggerSectionIds())
            );
        }
    }

    private String endpoint(int endpointType, int endpointId) {
        return endpointType + ":" + endpointId;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize line data list", ex);
        }
    }
}
