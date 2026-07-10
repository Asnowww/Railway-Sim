package com.railwaysim.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves config file paths for both project-root and backend working directories.
 */
public final class ConfigPathResolver {

    private ConfigPathResolver() {
    }

    public static Path resolve(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("Config path must not be blank");
        }

        Path primary = Path.of(configuredPath);
        if (Files.exists(primary)) {
            return primary;
        }

        for (Path candidate : alternateCandidates(configuredPath)) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        String fileName = configFileName(configuredPath);
        if (fileName != null) {
            Path located = locateInProjectTree(Path.of("").toAbsolutePath(), fileName);
            if (located != null) {
                return located;
            }
        }

        return primary;
    }

    private static Iterable<Path> alternateCandidates(String configuredPath) {
        String normalized = configuredPath.replace('\\', '/');
        if (normalized.startsWith("../config/")) {
            return java.util.List.of(
                Path.of("config/" + normalized.substring("../config/".length())),
                Path.of("backend/../config/" + normalized.substring("../config/".length()))
            );
        }
        if (normalized.startsWith("config/")) {
            return java.util.List.of(
                Path.of("../config/" + normalized.substring("config/".length()))
            );
        }
        return java.util.List.of();
    }

    private static String configFileName(String configuredPath) {
        String normalized = configuredPath.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private static Path locateInProjectTree(Path start, String fileName) {
        Path current = start.normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("config").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }
}
