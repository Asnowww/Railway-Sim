package com.railwaysim.localnet.powerpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
            PowerPointTable table = yamlMapper.readValue(path.toFile(), PowerPointTable.class);
            return table.points();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load power point table: " + path, ex);
        }
    }
}
