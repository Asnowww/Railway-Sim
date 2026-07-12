package com.railwaysim.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerConfigLoaderValidationTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsInternalCoverageGap() throws Exception {
        Path config = tempDir.resolve("power.yaml");
        Files.writeString(config, """
            nominalVoltage: 1500
            minimumVoltage: 1000
            cutoffVoltage: 900
            maxTractionCurrentAmps: 3000
            currentToVoltageDrop: 0.03
            sections:
              - id: P01
                startMeters: 0
                endMeters: 100
              - id: P02
                startMeters: 101
                endMeters: 200
            thirdRailSections:
              - id: TR-P01
                powerSectionId: P01
                startMeters: 0
                endMeters: 100
              - id: TR-P02
                powerSectionId: P02
                startMeters: 101
                endMeters: 200
            """);

        assertThatThrownBy(() -> new PowerConfigLoader().load(config, 200))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gap or overlap");
    }
}
