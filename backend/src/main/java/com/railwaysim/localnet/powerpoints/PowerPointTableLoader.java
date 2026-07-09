package com.railwaysim.localnet.powerpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PowerPointTableLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<PowerPointDefinition> load(String pointTablePath) {
        if (pointTablePath == null || pointTablePath.isBlank()) {
            return List.of();
        }
        Path path = Path.of(pointTablePath).normalize();
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                return loadCsv(path);
            }
            PowerPointTable table = yamlMapper.readValue(path.toFile(), PowerPointTable.class);
            return table.points();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load power point table: " + path, ex);
        }
    }

    private List<PowerPointDefinition> loadCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path).stream()
            .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
            .toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> headers = splitCsvLine(lines.get(0));
        List<PowerPointDefinition> definitions = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            List<String> values = splitCsvLine(lines.get(index));
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                row.put(normalizeHeader(headers.get(column)), column < values.size() ? values.get(column) : "");
            }
            definitions.add(new PowerPointDefinition(
                value(row, "pointid"),
                value(row, "name"),
                value(row, "direction"),
                value(row, "datatype"),
                value(row, "address"),
                doubleValue(row, "scale", 1),
                value(row, "defaultvalue"),
                value(row, "domaintarget"),
                value(row, "quality"),
                value(row, "targettype"),
                value(row, "targetid"),
                value(row, "targetfield"),
                value(row, "operationtype"),
                value(row, "desiredstate")
            ));
        }
        return definitions;
    }

    private List<String> splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        result.add(current.toString().trim());
        return result;
    }

    private String value(Map<String, String> row, String key) {
        return row.getOrDefault(key, "");
    }

    private double doubleValue(Map<String, String> row, String key, double fallback) {
        try {
            return Double.parseDouble(value(row, key));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String normalizeHeader(String header) {
        return header == null
            ? ""
            : header.trim().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
