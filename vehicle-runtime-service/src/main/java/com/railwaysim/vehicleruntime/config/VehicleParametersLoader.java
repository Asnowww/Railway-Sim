package com.railwaysim.vehicleruntime.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VehicleParametersLoader {

    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    @Bean
    public VehicleParameters vehicleParameters(VehicleRuntimeProperties properties) {
        return load(properties.getTrainParamsPath());
    }

    public static VehicleParameters load(String configuredPath) {
        Path sourcePath = resolve(configuredPath);
        try {
            byte[] rawBytes = Files.readAllBytes(sourcePath);
            ParameterFile file = YAML_MAPPER.readValue(rawBytes, ParameterFile.class);
            validate(file, sourcePath);
            return new VehicleParameters(
                file.trainType(),
                file.lengthMeters(),
                file.emptyMassKg(),
                file.maxLoadMassKg(),
                new VehicleParameters.Traction(
                    file.traction().maxPowerWatts(),
                    file.traction().maxTractionForceNewtons(),
                    file.traction().efficiency()
                ),
                new VehicleParameters.Brake(
                    file.brake().maxServiceBrakeForceNewtons(),
                    file.brake().maxEmergencyBrakeForceNewtons(),
                    file.brake().regenBrakeRatio(),
                    file.brake().regenEfficiency()
                ),
                new VehicleParameters.Resistance(
                    file.resistance().davisA(),
                    file.resistance().davisB(),
                    file.resistance().davisC()
                ),
                new VehicleParameters.Power(
                    file.power().nominalVoltage(),
                    file.power().minVoltage(),
                    file.power().cutoffVoltage()
                ),
                "sha256:" + sha256(rawBytes),
                sourcePath
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load vehicle parameters from " + sourcePath + ": " + exception.getMessage(), exception);
        }
    }

    private static Path resolve(String configuredPath) {
        String effectivePath = configuredPath == null || configuredPath.isBlank()
            ? "config/train_params.yaml"
            : configuredPath;
        Path requested = Path.of(effectivePath);
        if (requested.isAbsolute()) {
            if (Files.isRegularFile(requested)) {
                return requested.normalize();
            }
            throw new IllegalStateException("Vehicle parameter file does not exist: " + requested);
        }

        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            workingDirectory.resolve(requested).normalize(),
            workingDirectory.resolve("..").resolve(requested).normalize()
        );
        return candidates.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Vehicle parameter file does not exist; checked " + candidates
            ));
    }

    private static void validate(ParameterFile file, Path sourcePath) {
        if (file == null) {
            invalid(sourcePath, "$", "document is empty");
        }
        requireText(sourcePath, "trainType", file.trainType());
        requirePositive(sourcePath, "lengthMeters", file.lengthMeters());
        requirePositive(sourcePath, "emptyMassKg", file.emptyMassKg());
        requirePositive(sourcePath, "maxLoadMassKg", file.maxLoadMassKg());
        if (file.traction() == null) {
            invalid(sourcePath, "traction", "section is required");
        }
        requirePositive(sourcePath, "traction.maxPowerWatts", file.traction().maxPowerWatts());
        requirePositive(sourcePath, "traction.maxTractionForceNewtons", file.traction().maxTractionForceNewtons());
        requireEfficiency(sourcePath, "traction.efficiency", file.traction().efficiency());
        if (file.brake() == null) {
            invalid(sourcePath, "brake", "section is required");
        }
        requirePositive(sourcePath, "brake.maxServiceBrakeForceNewtons", file.brake().maxServiceBrakeForceNewtons());
        requirePositive(sourcePath, "brake.maxEmergencyBrakeForceNewtons", file.brake().maxEmergencyBrakeForceNewtons());
        requireRange(sourcePath, "brake.regenBrakeRatio", file.brake().regenBrakeRatio(), 0, 1);
        requireEfficiency(sourcePath, "brake.regenEfficiency", file.brake().regenEfficiency());
        if (file.resistance() == null) {
            invalid(sourcePath, "resistance", "section is required");
        }
        requireNonNegative(sourcePath, "resistance.davisA", file.resistance().davisA());
        requireNonNegative(sourcePath, "resistance.davisB", file.resistance().davisB());
        requireNonNegative(sourcePath, "resistance.davisC", file.resistance().davisC());
        if (file.power() == null) {
            invalid(sourcePath, "power", "section is required");
        }
        requirePositive(sourcePath, "power.nominalVoltage", file.power().nominalVoltage());
        requirePositive(sourcePath, "power.minVoltage", file.power().minVoltage());
        requirePositive(sourcePath, "power.cutoffVoltage", file.power().cutoffVoltage());
        if (!(file.power().cutoffVoltage() < file.power().minVoltage()
            && file.power().minVoltage() <= file.power().nominalVoltage())) {
            invalid(sourcePath, "power", "must satisfy cutoffVoltage < minVoltage <= nominalVoltage");
        }
    }

    private static void requireText(Path path, String field, String value) {
        if (value == null || value.isBlank()) {
            invalid(path, field, "must not be blank");
        }
    }

    private static void requirePositive(Path path, String field, double value) {
        if (!Double.isFinite(value) || value <= 0) {
            invalid(path, field, "must be a finite value greater than 0");
        }
    }

    private static void requireNonNegative(Path path, String field, double value) {
        if (!Double.isFinite(value) || value < 0) {
            invalid(path, field, "must be a finite value greater than or equal to 0");
        }
    }

    private static void requireEfficiency(Path path, String field, double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            invalid(path, field, "must be in (0, 1]");
        }
    }

    private static void requireRange(Path path, String field, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            invalid(path, field, "must be in [" + min + ", " + max + "]");
        }
    }

    private static void invalid(Path path, String field, String reason) {
        throw new IllegalStateException("Invalid vehicle parameter " + field + " in " + path + ": " + reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record ParameterFile(
        String trainType,
        double lengthMeters,
        double emptyMassKg,
        double maxLoadMassKg,
        TractionFile traction,
        BrakeFile brake,
        ResistanceFile resistance,
        PowerFile power
    ) {
    }

    private record TractionFile(
        double maxPowerWatts,
        double maxTractionForceNewtons,
        double efficiency
    ) {
    }

    private record BrakeFile(
        double maxServiceBrakeForceNewtons,
        double maxEmergencyBrakeForceNewtons,
        double regenBrakeRatio,
        double regenEfficiency
    ) {
    }

    private record ResistanceFile(double davisA, double davisB, double davisC) {
    }

    private record PowerFile(double nominalVoltage, double minVoltage, double cutoffVoltage) {
    }
}
