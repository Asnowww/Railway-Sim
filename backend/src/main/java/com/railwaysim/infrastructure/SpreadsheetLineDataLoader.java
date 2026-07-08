package com.railwaysim.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class SpreadsheetLineDataLoader {

    private static final int DATA_START_ROW = 4;
    private static final String SEG_SHEET = "Seg表";
    private static final String POINT_SHEET = "点表";
    private static final String SPEED_SHEET = "静态限速表";
    private static final String GRADIENT_SHEET = "坡度表";
    private static final String SWITCH_SHEET = "道岔表";
    private static final String STATION_SHEET = "车站表";
    private static final String PLATFORM_SHEET = "站台表";
    private static final String SIGNAL_SHEET = "信号机表";
    private static final String BALISE_SHEET = "应答器表";
    private static final String ROUTE_SHEET = "进路表";

    public OperationalLineData load(Path path, double defaultSpeedLimitMetersPerSecond) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Map<Integer, SegmentProjection> segmentProjections = buildSegmentProjections(workbook);
            List<OperationalLineData.SpeedLimitZone> speedLimitZones = buildSpeedLimitZones(
                workbook,
                segmentProjections
            );
            Map<Integer, Double> minSpeedBySegment = minimumSpeedLimitBySegment(speedLimitZones);

            List<OperationalLineData.TrackSegmentDefinition> trackSegments = segmentProjections.values().stream()
                .map(segment -> new OperationalLineData.TrackSegmentDefinition(
                    segment.segmentId(),
                    segment.rawSegmentId(),
                    segment.startMeters(),
                    segment.endMeters(),
                    segment.lengthMeters(),
                    minSpeedBySegment.getOrDefault(segment.rawSegmentId(), defaultSpeedLimitMetersPerSecond),
                    segment.startEndpointType(),
                    segment.startEndpointId(),
                    segment.endEndpointType(),
                    segment.endEndpointId(),
                    segment.forwardNeighborIds(),
                    segment.sideNeighborIds(),
                "", "", "main"
                ))
                .toList();

            List<OperationalLineData.PlatformDefinition> platforms = buildPlatforms(workbook, segmentProjections);
            Map<String, OperationalLineData.PlatformDefinition> platformById = platforms.stream()
                .collect(LinkedHashMap::new, (map, platform) -> map.put(platform.id(), platform), Map::putAll);

            String fileName = stripExtension(path.getFileName().toString());
            return new OperationalLineData(
                sanitizeLineId(fileName),
                fileName,
                buildPoints(workbook),
                trackSegments,
                speedLimitZones,
                buildGradientZones(workbook, segmentProjections),
                buildSwitches(workbook),
                buildStations(workbook, platformById),
                platforms,
                buildSignals(workbook, segmentProjections),
                buildBalises(workbook, segmentProjections),
                buildRoutes(workbook)
            );
        }
    }

    private Map<Integer, SegmentProjection> buildSegmentProjections(Workbook workbook) {
        List<List<String>> rows = sheetRows(workbook, SEG_SHEET, DATA_START_ROW);
        Map<Integer, SegmentProjection> projections = new LinkedHashMap<>();
        double cursorMeters = 0;
        for (List<String> row : rows) {
            Integer rawSegmentId = intValue(row, 0);
            Double lengthMeters = centimetersToMeters(row, 1);
            Integer startEndpointType = intValue(row, 2);
            Integer startEndpointId = intValue(row, 3);
            Integer endEndpointType = intValue(row, 4);
            Integer endEndpointId = intValue(row, 5);
            if (rawSegmentId == null || lengthMeters == null) {
                continue;
            }

            double startMeters = cursorMeters;
            double endMeters = cursorMeters + lengthMeters;
            cursorMeters = endMeters;
            projections.put(rawSegmentId, new SegmentProjection(
                rawSegmentId,
                segmentRef(rawSegmentId),
                startMeters,
                endMeters,
                lengthMeters,
                startEndpointType == null ? 0 : startEndpointType,
                startEndpointId == null ? 0 : startEndpointId,
                endEndpointType == null ? 0 : endEndpointType,
                endEndpointId == null ? 0 : endEndpointId,
                neighborRefs(stringValue(row, 6), stringValue(row, 8)),
                neighborRefs(stringValue(row, 7), stringValue(row, 9))
            ));
        }
        return projections;
    }

    private List<OperationalLineData.PointDefinition> buildPoints(Workbook workbook) {
        List<OperationalLineData.PointDefinition> points = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, POINT_SHEET, DATA_START_ROW)) {
            Integer rawPointId = intValue(row, 0);
            Double kilometerMarkMeters = centimetersToMeters(row, 3);
            if (rawPointId == null || kilometerMarkMeters == null) {
                continue;
            }
            points.add(new OperationalLineData.PointDefinition(
                pointRef(rawPointId),
                stringValue(row, 1),
                stringValue(row, 2),
                kilometerMarkMeters,
                stringValue(row, 12)
            ));
        }
        return points;
    }

    private List<OperationalLineData.SpeedLimitZone> buildSpeedLimitZones(
        Workbook workbook,
        Map<Integer, SegmentProjection> segmentProjections
    ) {
        List<OperationalLineData.SpeedLimitZone> zones = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, SPEED_SHEET, DATA_START_ROW)) {
            Integer rawZoneId = intValue(row, 0);
            Integer rawSegmentId = intValue(row, 1);
            Double startOffsetMeters = centimetersToMeters(row, 2);
            Double endOffsetMeters = centimetersToMeters(row, 3);
            Double speedLimitMetersPerSecond = centimetersPerSecondToMetersPerSecond(row, 5);
            SegmentProjection segment = rawSegmentId == null ? null : segmentProjections.get(rawSegmentId);
            if (
                rawZoneId == null ||
                    segment == null ||
                    startOffsetMeters == null ||
                    endOffsetMeters == null ||
                    speedLimitMetersPerSecond == null
            ) {
                continue;
            }
            double startMeters = Math.min(
                segment.startMeters() + startOffsetMeters,
                segment.startMeters() + endOffsetMeters
            );
            double endMeters = Math.max(
                segment.startMeters() + startOffsetMeters,
                segment.startMeters() + endOffsetMeters
            );
            zones.add(new OperationalLineData.SpeedLimitZone(
                "LIMIT-" + rawZoneId,
                segment.segmentId(),
                clamp(startMeters, segment.startMeters(), segment.endMeters()),
                clamp(endMeters, segment.startMeters(), segment.endMeters()),
                speedLimitMetersPerSecond,
                switchRef(stringValue(row, 4))
            ));
        }
        return zones;
    }

    private List<OperationalLineData.GradientZone> buildGradientZones(
        Workbook workbook,
        Map<Integer, SegmentProjection> segmentProjections
    ) {
        List<OperationalLineData.GradientZone> zones = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, GRADIENT_SHEET, DATA_START_ROW)) {
            Integer rawGradientId = intValue(row, 0);
            Integer startSegmentId = intValue(row, 1);
            Double startOffsetMeters = centimetersToMeters(row, 2);
            Integer endSegmentId = intValue(row, 3);
            Double endOffsetMeters = centimetersToMeters(row, 4);
            Integer rawPermilleValue = intValue(row, 11);
            String directionCode = stringValue(row, 12);
            SegmentProjection startSegment = startSegmentId == null ? null : segmentProjections.get(startSegmentId);
            SegmentProjection endSegment = endSegmentId == null ? null : segmentProjections.get(endSegmentId);
            if (
                rawGradientId == null ||
                    startSegment == null ||
                    endSegment == null ||
                    startOffsetMeters == null ||
                    endOffsetMeters == null ||
                    rawPermilleValue == null
            ) {
                continue;
            }

            double startMeters = startSegment.startMeters() + startOffsetMeters;
            double endMeters = endSegment.startMeters() + endOffsetMeters;
            if (endMeters < startMeters) {
                double swapped = startMeters;
                startMeters = endMeters;
                endMeters = swapped;
            }

            zones.add(new OperationalLineData.GradientZone(
                "GRADIENT-" + rawGradientId,
                startMeters,
                endMeters,
                directionMultiplier(directionCode) * rawPermilleValue / 1000.0,
                rawPermilleValue,
                directionCode
            ));
        }
        return zones;
    }

    private List<OperationalLineData.SwitchDefinition> buildSwitches(Workbook workbook) {
        List<OperationalLineData.SwitchDefinition> switches = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, SWITCH_SHEET, DATA_START_ROW)) {
            Integer rawSwitchId = intValue(row, 0);
            if (rawSwitchId == null) {
                continue;
            }
            switches.add(new OperationalLineData.SwitchDefinition(
                switchRef(rawSwitchId),
                stringValue(row, 1),
                switchRef(stringValue(row, 2)),
                stringValue(row, 3),
                segmentRef(stringValue(row, 4)),
                segmentRef(stringValue(row, 5)),
                segmentRef(stringValue(row, 6)),
                centimetersPerSecondToMetersPerSecond(row, 7, 0),
                stringValue(row, 8)
            ));
        }
        return switches;
    }

    private List<OperationalLineData.PlatformDefinition> buildPlatforms(
        Workbook workbook,
        Map<Integer, SegmentProjection> segmentProjections
    ) {
        List<OperationalLineData.PlatformDefinition> platforms = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, PLATFORM_SHEET, DATA_START_ROW)) {
            Integer rawPlatformId = intValue(row, 0);
            Integer rawSegmentId = intValue(row, 2);
            if (rawPlatformId == null) {
                continue;
            }
            SegmentProjection segment = rawSegmentId == null ? null : segmentProjections.get(rawSegmentId);
            double centerMeters = segment == null
                ? parseKilometerMarkMeters(stringValue(row, 1))
                : segment.startMeters() + segment.lengthMeters() / 2.0;
            platforms.add(new OperationalLineData.PlatformDefinition(
                platformRef(rawPlatformId),
                centerMeters,
                rawSegmentId == null ? null : segmentRef(rawSegmentId),
                stringValue(row, 3),
                stringValue(row, 1),
                stringValue(row, 12)
            ));
        }
        return platforms;
    }

    private List<OperationalLineData.StationDefinition> buildStations(
        Workbook workbook,
        Map<String, OperationalLineData.PlatformDefinition> platformById
    ) {
        List<OperationalLineData.StationDefinition> stations = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, STATION_SHEET, DATA_START_ROW)) {
            Integer rawStationId = intValue(row, 0);
            Integer platformCount = intValue(row, 2);
            if (rawStationId == null) {
                continue;
            }
            List<String> platformIds = collectRefs(row, 3, platformCount == null ? 0 : platformCount, this::platformRef);
            double centerMeters = platformIds.stream()
                .map(platformById::get)
                .filter(platform -> platform != null)
                .mapToDouble(OperationalLineData.PlatformDefinition::centerMeters)
                .average()
                .orElse(0);
            stations.add(new OperationalLineData.StationDefinition(
                stationRef(rawStationId),
                stringValue(row, 1),
                centerMeters,
                platformIds
            ));
        }
        return stations;
    }

    private List<OperationalLineData.SignalDefinition> buildSignals(
        Workbook workbook,
        Map<Integer, SegmentProjection> segmentProjections
    ) {
        List<OperationalLineData.SignalDefinition> signals = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, SIGNAL_SHEET, DATA_START_ROW)) {
            Integer rawSignalId = intValue(row, 0);
            Integer rawSegmentId = intValue(row, 4);
            Double offsetMeters = centimetersToMeters(row, 5);
            SegmentProjection segment = rawSegmentId == null ? null : segmentProjections.get(rawSegmentId);
            if (rawSignalId == null || segment == null || offsetMeters == null) {
                continue;
            }
            signals.add(new OperationalLineData.SignalDefinition(
                signalRef(rawSignalId),
                stringValue(row, 1),
                stringValue(row, 2),
                stringValue(row, 3),
                segment.segmentId(),
                clamp(segment.startMeters() + offsetMeters, segment.startMeters(), segment.endMeters()),
                stringValue(row, 6),
                stringValue(row, 7),
                stringValue(row, 8)
            ));
        }
        return signals;
    }

    private List<OperationalLineData.BaliseDefinition> buildBalises(
        Workbook workbook,
        Map<Integer, SegmentProjection> segmentProjections
    ) {
        List<OperationalLineData.BaliseDefinition> balises = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, BALISE_SHEET, DATA_START_ROW)) {
            Integer rawBaliseId = intValue(row, 0);
            Integer rawSegmentId = intValue(row, 3);
            Double offsetMeters = centimetersToMeters(row, 4);
            SegmentProjection segment = rawSegmentId == null ? null : segmentProjections.get(rawSegmentId);
            if (rawBaliseId == null || segment == null || offsetMeters == null) {
                continue;
            }
            balises.add(new OperationalLineData.BaliseDefinition(
                baliseRef(rawBaliseId),
                stringValue(row, 1),
                stringValue(row, 2),
                segment.segmentId(),
                clamp(segment.startMeters() + offsetMeters, segment.startMeters(), segment.endMeters()),
                stringValue(row, 5),
                stringValue(row, 6),
                signalRef(stringValue(row, 7)),
                stringValue(row, 8)
            ));
        }
        return balises;
    }

    private List<OperationalLineData.RouteDefinition> buildRoutes(Workbook workbook) {
        List<OperationalLineData.RouteDefinition> routes = new ArrayList<>();
        for (List<String> row : sheetRows(workbook, ROUTE_SHEET, DATA_START_ROW)) {
            Integer rawRouteId = intValue(row, 0);
            if (rawRouteId == null) {
                continue;
            }
            routes.add(new OperationalLineData.RouteDefinition(
                routeRef(rawRouteId),
                stringValue(row, 1),
                stringValue(row, 2),
                signalRef(stringValue(row, 3)),
                signalRef(stringValue(row, 4)),
                collectRefs(row, 6, intValue(row, 5), value -> prefixedRef(value, "AXLE-")),
                collectRefs(row, 27, intValue(row, 26), value -> prefixedRef(value, "PROTECT-")),
                collectRefs(row, 33, intValue(row, 32), value -> prefixedRef(value, "APPROACH-POINT-")),
                collectRefs(row, 39, intValue(row, 38), value -> prefixedRef(value, "APPROACH-CBTC-")),
                collectRefs(row, 45, intValue(row, 44), value -> prefixedRef(value, "TRIGGER-POINT-")),
                collectRefs(row, 51, intValue(row, 50), value -> prefixedRef(value, "TRIGGER-CBTC-"))
            ));
        }
        return routes;
    }

    private Map<Integer, Double> minimumSpeedLimitBySegment(List<OperationalLineData.SpeedLimitZone> speedLimitZones) {
        Map<Integer, Double> limits = new LinkedHashMap<>();
        for (OperationalLineData.SpeedLimitZone zone : speedLimitZones) {
            Integer rawSegmentId = numericSuffix(zone.segmentId());
            if (rawSegmentId == null) {
                continue;
            }
            limits.merge(rawSegmentId, zone.speedLimitMetersPerSecond(), Math::min);
        }
        return limits;
    }

    private List<List<String>> sheetRows(Workbook workbook, String sheetName, int dataStartRow) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            return List.of();
        }

        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = dataStartRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || row.getLastCellNum() < 0) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                String value = formatter.formatCellValue(row.getCell(columnIndex)).trim();
                values.add(value.isBlank() ? null : value);
            }
            if (values.stream().anyMatch(value -> value != null)) {
                rows.add(values);
            }
        }
        return rows;
    }

    private List<String> collectRefs(
        List<String> row,
        int firstColumnIndex,
        Integer declaredCount,
        Function<String, String> refMapper
    ) {
        if (declaredCount == null || declaredCount <= 0) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (int offset = 0; offset < declaredCount; offset++) {
            String ref = refMapper.apply(stringValue(row, firstColumnIndex + offset));
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private List<String> neighborRefs(String leftRawValue, String rightRawValue) {
        List<String> refs = new ArrayList<>();
        String left = segmentRef(leftRawValue);
        if (left != null) {
            refs.add(left);
        }
        String right = segmentRef(rightRawValue);
        if (right != null) {
            refs.add(right);
        }
        return refs;
    }

    private String prefixedRef(String rawValue, String prefix) {
        if (invalidDataValue(rawValue)) {
            return null;
        }
        return prefix + rawValue.trim();
    }

    private String pointRef(Integer rawId) {
        return rawId == null ? null : "POINT-" + rawId;
    }

    private String segmentRef(Integer rawId) {
        return rawId == null ? null : "SEG-" + rawId;
    }

    private String segmentRef(String rawValue) {
        Integer rawId = numericValue(rawValue);
        return rawId == null ? null : segmentRef(rawId);
    }

    private String switchRef(Integer rawId) {
        return rawId == null ? null : "SW-" + rawId;
    }

    private String switchRef(String rawValue) {
        Integer rawId = numericValue(rawValue);
        return rawId == null ? null : switchRef(rawId);
    }

    private String stationRef(Integer rawId) {
        return rawId == null ? null : "ST-" + rawId;
    }

    private String platformRef(Integer rawId) {
        return rawId == null ? null : "PLAT-" + rawId;
    }

    private String platformRef(String rawValue) {
        Integer rawId = numericValue(rawValue);
        return rawId == null ? null : platformRef(rawId);
    }

    private String signalRef(Integer rawId) {
        return rawId == null ? null : "SIG-" + rawId;
    }

    private String signalRef(String rawValue) {
        Integer rawId = numericValue(rawValue);
        return rawId == null ? null : signalRef(rawId);
    }

    private String baliseRef(Integer rawId) {
        return rawId == null ? null : "BAL-" + rawId;
    }

    private String routeRef(Integer rawId) {
        return rawId == null ? null : "ROUTE-" + rawId;
    }

    private Integer numericSuffix(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return null;
        }
        return Integer.parseInt(digits);
    }

    private Integer numericValue(String rawValue) {
        if (invalidDataValue(rawValue)) {
            return null;
        }
        return Integer.parseInt(rawValue.trim().replaceAll("\\.0+$", ""));
    }

    private Integer intValue(List<String> row, int columnIndex) {
        return numericValue(stringValue(row, columnIndex));
    }

    private Double centimetersToMeters(List<String> row, int columnIndex) {
        String value = stringValue(row, columnIndex);
        if (invalidDataValue(value)) {
            return null;
        }
        return Double.parseDouble(value) / 100.0;
    }

    private Double centimetersPerSecondToMetersPerSecond(List<String> row, int columnIndex) {
        String value = stringValue(row, columnIndex);
        if (invalidDataValue(value)) {
            return null;
        }
        return Double.parseDouble(value) / 100.0;
    }

    private double centimetersPerSecondToMetersPerSecond(List<String> row, int columnIndex, double fallbackValue) {
        Double value = centimetersPerSecondToMetersPerSecond(row, columnIndex);
        return value == null ? fallbackValue : value;
    }

    private String stringValue(List<String> row, int columnIndex) {
        if (columnIndex < 0 || columnIndex >= row.size()) {
            return null;
        }
        return row.get(columnIndex);
    }

    private boolean invalidDataValue(String value) {
        return value == null || value.isBlank() || "65535".equals(value.trim());
    }

    private double directionMultiplier(String directionCode) {
        return "0x55".equalsIgnoreCase(directionCode) ? -1.0 : 1.0;
    }

    private double parseKilometerMarkMeters(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0;
        }
        String normalized = rawValue.trim().toUpperCase(Locale.ROOT).replace("K", "");
        if (!normalized.contains("+")) {
            return 0;
        }
        String[] parts = normalized.split("\\+");
        if (parts.length != 2) {
            return 0;
        }
        double kilometers = Double.parseDouble(parts[0]);
        double meters = Double.parseDouble(parts[1]);
        return kilometers * 1000 + meters;
    }

    private String sanitizeLineId(String rawValue) {
        String sanitized = rawValue.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return sanitized.isBlank() ? "imported-line" : sanitized.replaceAll("(^-+|-+$)", "");
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record SegmentProjection(
        int rawSegmentId,
        String segmentId,
        double startMeters,
        double endMeters,
        double lengthMeters,
        int startEndpointType,
        int startEndpointId,
        int endEndpointType,
        int endEndpointId,
        List<String> forwardNeighborIds,
        List<String> sideNeighborIds
    ) {
    }
}
