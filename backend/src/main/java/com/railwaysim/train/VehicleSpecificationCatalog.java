package com.railwaysim.train;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Central-side vehicle geometry/identity metadata; no vehicle dynamics are evaluated here. */
@Component
public class VehicleSpecificationCatalog {

    private final VehicleSpecification specification;

    public VehicleSpecificationCatalog(
        @Value("${vehicle.train-params-path:config/train_params.yaml}") String configuredPath
    ) {
        this.specification = load(configuredPath);
    }

    public VehicleSpecification specification() {
        return specification;
    }

    public static VehicleSpecification load(String configuredPath) {
        Path source = resolve(configuredPath);
        try {
            byte[] bytes = Files.readAllBytes(source);
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(bytes);
            String schemaVersion = requiredText(root, "parameterSchemaVersion", source);
            if (!"2".equals(schemaVersion)) {
                throw new IllegalStateException("Unsupported vehicle parameter schema " + schemaVersion);
            }
            String trainType = requiredText(root, "trainType", source);
            double lengthMeters = requiredPositive(root, "lengthMeters", source);
            double emptyMassKg = requiredPositive(root.path("formation").path("loadCasesKg"), "AW0", source);
            double ratedMassKg = requiredPositive(root.path("formation").path("loadCasesKg"), "AW2", source);
            return new VehicleSpecification(
                schemaVersion,
                trainType,
                lengthMeters,
                emptyMassKg,
                ratedMassKg - emptyMassKg,
                "sha256:" + sha256(bytes),
                source
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load vehicle specification from " + source, exception);
        }
    }

    private static Path resolve(String configuredPath) {
        Path requested = Path.of(
            configuredPath == null || configuredPath.isBlank()
                ? "config/train_params.yaml"
                : configuredPath
        );
        if (requested.isAbsolute()) {
            if (Files.isRegularFile(requested)) {
                return requested.normalize();
            }
            throw new IllegalStateException("Vehicle parameter file does not exist: " + requested);
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            cwd.resolve(requested).normalize(),
            cwd.resolve("..").resolve(requested).normalize()
        );
        return candidates.stream().filter(Files::isRegularFile).findFirst()
            .orElseThrow(() -> new IllegalStateException("Vehicle parameter file does not exist; checked " + candidates));
    }

    private static String requiredText(JsonNode root, String field, Path source) {
        String value = root.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Missing vehicle parameter " + field + " in " + source);
        }
        return value;
    }

    private static double requiredPositive(JsonNode root, String field, Path source) {
        double value = root.path(field).asDouble(Double.NaN);
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalStateException("Invalid vehicle parameter " + field + " in " + source);
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record VehicleSpecification(
        String parameterSchemaVersion,
        String trainType,
        double lengthMeters,
        double emptyMassKg,
        double maxLoadMassKg,
        String parameterSetId,
        Path sourcePath
    ) {
    }
}
