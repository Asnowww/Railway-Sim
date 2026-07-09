package com.railwaysim.localnet.powerpoints;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LocalSimPowerPointDriver implements PowerPointDriver {

    private final Map<String, String> values = new LinkedHashMap<>();

    public LocalSimPowerPointDriver(List<PowerPointDefinition> definitions) {
        for (PowerPointDefinition definition : definitions) {
            values.put(definition.pointId(), definition.defaultValue());
        }
    }

    @Override
    public synchronized List<PowerPointValue> snapshot(List<PowerPointDefinition> definitions) {
        Instant now = Instant.now();
        return definitions.stream()
            .filter(PowerPointDefinition::readPoint)
            .map(definition -> new PowerPointValue(
                definition.pointId(),
                values.getOrDefault(definition.pointId(), definition.defaultValue()),
                definition.quality(),
                now
            ))
            .toList();
    }

    @Override
    public synchronized PowerPointValue write(PowerPointDefinition definition, String value) {
        String nextValue = value == null || value.isBlank() ? definition.desiredState() : value.trim();
        values.put(definition.pointId(), nextValue);
        return new PowerPointValue(definition.pointId(), nextValue, definition.quality(), Instant.now());
    }
}
