package com.railwaysim.localnet.powerpoints;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerPointTableLoaderTests {

    @TempDir
    Path tempDir;

    @Test
    void loadsYamlPointTable() throws Exception {
        Path file = tempDir.resolve("points.yaml");
        Files.writeString(file, """
            points:
              - pointId: P01_VOLTAGE
                direction: READ
                dataType: DOUBLE
                address: SIM:P01:VOLTAGE
                defaultValue: "1500"
                targetType: THIRD_RAIL_SECTION
                targetId: P01
                targetField: voltage
            """);

        List<PowerPointDefinition> definitions = new PowerPointTableLoader().load(file.toString());

        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).pointId()).isEqualTo("P01_VOLTAGE");
        assertThat(definitions.get(0).readPoint()).isTrue();
        assertThat(definitions.get(0).targetId()).isEqualTo("P01");
    }
}
