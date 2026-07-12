package com.railwaysim.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.track.TrackService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlLineDataLoaderTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsDisconnectedRoutesDuringLoad() throws Exception {
        Path yaml = tempDir.resolve("line.yaml");
        Files.writeString(yaml, """
            line: { id: test, name: Test }
            segments:
              - { id: T01, from: N1, to: N2, start_meters: 0, end_meters: 100, speed_limit_meters_per_second: 10 }
              - { id: T02, from: N3, to: N4, start_meters: 100, end_meters: 200, speed_limit_meters_per_second: 10 }
            signals:
              - { id: S1, position_meters: 0, direction: FORWARD, segment_id: T01 }
              - { id: S2, position_meters: 200, direction: FORWARD, segment_id: T02 }
            routes:
              - { id: R_BAD, start_signal: S1, end_signal: S2, axle_section_ids: [T01, T02] }
            """);

        assertThatThrownBy(() -> new YamlLineDataLoader().load(yaml))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("R_BAD is disconnected between T01 and T02");
    }

    @Test
    void demoTopologyContainsOnlyEstablishableRoutes() throws Exception {
        OperationalLineData line = new YamlLineDataLoader().load(Path.of("..", "config", "line-topology-demo.yaml"));

        for (OperationalLineData.RouteDefinition route : line.routes()) {
            StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(line, emptyPowerData());
            TrackService trackService = new TrackService(catalog, new SimulationProperties());
            trackService.reset();
            RouteInterlockingService interlocking = new RouteInterlockingService(catalog, trackService);
            interlocking.init();

            assertThat(interlocking.establishRoute(route.id(), "TR-TEST"))
                .as("route %s", route.id())
                .isNull();
        }
    }

    private static OperationalPowerData emptyPowerData() {
        return new OperationalPowerData(
            1500, 1000, 900, 3000, 3500, 0.02, true, "BRAKE_RESISTOR",
            java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of()
        );
    }
}
