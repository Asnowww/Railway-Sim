package com.railwaysim.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigPathResolverTests {

    @Test
    void resolvesLineBranchDemoFromProjectRootStylePath() {
        Path resolved = ConfigPathResolver.resolve("../config/line-branch-demo.yaml");
        assertTrue(Files.exists(resolved), () -> "Expected config file at " + resolved.toAbsolutePath());
    }

    @Test
    void resolvesPowerConfigFromProjectRootStylePath() {
        Path resolved = ConfigPathResolver.resolve("../config/power_third_rail.yaml");
        assertTrue(Files.exists(resolved), () -> "Expected config file at " + resolved.toAbsolutePath());
    }
}
