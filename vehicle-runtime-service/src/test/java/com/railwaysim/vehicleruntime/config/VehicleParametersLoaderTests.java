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

        assertThat(parameters.parameterSchemaVersion()).isEqualTo("2");
        assertThat(parameters.emptyMassKg()).isEqualTo(225_000);
        assertThat(parameters.maxLoadMassKg()).isEqualTo(76_000);
        assertThat(parameters.maxOperatingLoadMassKg()).isEqualTo(104_000);
        assertThat(parameters.lengthMeters()).isEqualTo(118.0);
        assertThat(parameters.curves().pointCount()).isEqualTo(52);
        assertThat(parameters.drivetrain().gearRatio()).isEqualTo(6.5);
        assertThat(parameters.drivetrain().tractionTotalEfficiency()).isEqualTo(0.882);
        assertThat(parameters.maxCurveMechanicalTractionPowerWatts()).isEqualTo(4_336_000);
        assertThat(parameters.curveSetId()).startsWith("sha256:");
        assertThat(parameters.parameterSetId()).isEqualTo(expectedHash);
    }

    @Test
    void invalidFieldReportsExactYamlPath() throws Exception {
        Path invalid = tempDirectory.resolve("invalid-train-params.yaml");
        Files.writeString(
            invalid,
            Files.readString(projectPath("config/train_params.yaml"), StandardCharsets.UTF_8)
                .replace("tractionTotalEfficiency: 0.882", "tractionTotalEfficiency: 0"),
            StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> VehicleParametersLoader.load(invalid.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("drivetrain.tractionTotalEfficiency")
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
                .replace("  tractionTotalEfficiency: 0.882", "  tractionTotalEfficiency: 0.882\n  unsupportedEfficiency: 0.5"),
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
