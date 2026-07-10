package com.railwaysim.vehicleruntime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VehicleParametersLoaderTests {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsCanonicalYamlAndHashesRawBytes() throws Exception {
        Path source = projectPath("config/train_params.yaml");

        VehicleParameters parameters = VehicleParametersLoader.load(source.toString());
        String expectedHash = "sha256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))
        );

        assertThat(parameters.emptyMassKg()).isEqualTo(198_000);
        assertThat(parameters.maxLoadMassKg()).isEqualTo(72_000);
        assertThat(parameters.traction().maxPowerWatts()).isEqualTo(3_200_000);
        assertThat(parameters.traction().efficiency()).isEqualTo(0.88);
        assertThat(parameters.parameterSetId()).isEqualTo(expectedHash);
    }

    @Test
    void invalidFieldReportsExactYamlPath() throws Exception {
        Path invalid = tempDirectory.resolve("invalid-train-params.yaml");
        Files.writeString(
            invalid,
            Files.readString(projectPath("config/train_params.yaml"), StandardCharsets.UTF_8)
                .replace("efficiency: 0.88", "efficiency: 0"),
            StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> VehicleParametersLoader.load(invalid.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("traction.efficiency")
            .hasMessageContaining(invalid.toString());
    }

    @Test
    void inconsistentVoltageThresholdsAreRejected() throws Exception {
        Path invalid = tempDirectory.resolve("invalid-voltage.yaml");
        Files.writeString(
            invalid,
            Files.readString(projectPath("config/train_params.yaml"), StandardCharsets.UTF_8)
                .replace("cutoffVoltage: 900", "cutoffVoltage: 1100"),
            StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> VehicleParametersLoader.load(invalid.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cutoffVoltage < minVoltage <= nominalVoltage");
    }

    @Test
    void unknownParameterIsRejected() throws Exception {
        Path invalid = tempDirectory.resolve("unknown-field.yaml");
        Files.writeString(
            invalid,
            Files.readString(projectPath("config/train_params.yaml"), StandardCharsets.UTF_8)
                .replace("  efficiency: 0.88", "  efficiency: 0.88\n  unsupportedEfficiency: 0.5"),
            StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> VehicleParametersLoader.load(invalid.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unsupportedEfficiency");
    }

    private Path projectPath(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve(relativePath);
        return Files.exists(direct) ? direct : workingDirectory.getParent().resolve(relativePath);
    }
}
