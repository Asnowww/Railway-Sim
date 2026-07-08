package com.railwaysim.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteInterlockingServiceTests {

    @Test
    void initLoadsRoutesBeforeSimulationReset() {
        Fixture fixture = fixture(lineDataWithDirectRoute());

        fixture.interlocking.init();

        assertThat(fixture.interlocking.states())
            .extracting(RouteState::routeId)
            .containsExactly("R_MAIN", "R_BRANCH");
    }

    @Test
    void touchRoutesUsesAxleToSegmentMappingForExcelStyleRouteIds() {
        Fixture fixture = fixture(lineDataWithAxleRoute());
        fixture.interlocking.init();

        fixture.interlocking.touchRoutes(List.of(train("TR-1", 50)));

        assertThat(fixture.interlocking.state("R_AXLE").status()).isEqualTo(RouteStatus.ESTABLISHED);
        assertThat(fixture.interlocking.establishedSegmentPathForTrain("TR-1"))
            .containsExactly("SEG-1", "SEG-3");
    }

    @Test
    void establishingDivergingRouteThrowsAndLocksSwitchToReverse() {
        Fixture fixture = fixture(lineDataWithDirectRoute());
        fixture.interlocking.init();

        String rejection = fixture.interlocking.establishRoute("R_BRANCH", "TR-1");

        assertThat(rejection).isNull();
        SwitchState sw = fixture.trackService.switchStates().get(0);
        assertThat(sw.position()).isEqualTo(SwitchPosition.REVERSE);
        assertThat(sw.activeSegmentId()).isEqualTo("SEG-3");
        assertThat(sw.locked()).isTrue();
    }

    @Test
    void movementAuthorityReservesAlongEstablishedDivergingRoute() {
        Fixture fixture = fixture(lineDataWithDirectRoute());
        fixture.interlocking.init();
        fixture.interlocking.establishRoute("R_BRANCH", "TR-1");
        TrainState train = train("TR-1", 50);
        fixture.trackService.updateOccupancy(List.of(train));

        fixture.signalService.calculateAuthorities(
            List.of(train),
            fixture.trackService.constraintsForTrains(List.of(train)),
            List.of(DispatchConstraint.none("TR-1"))
        );

        assertThat(occupancy(fixture.trackService, "SEG-2")).isEqualTo(TrackOccupancy.FREE);
        assertThat(occupancy(fixture.trackService, "SEG-3")).isEqualTo(TrackOccupancy.RESERVED);
    }

    private static Fixture fixture(OperationalLineData lineData) {
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData, emptyPowerData());
        SimulationProperties properties = new SimulationProperties();
        properties.setSafetyGapMeters(0);
        TrackService trackService = new TrackService(catalog, properties);
        trackService.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(catalog, trackService);
        SignalService signalService = new SignalService(properties, catalog, trackService, interlocking);
        return new Fixture(trackService, interlocking, signalService);
    }

    private static OperationalPowerData emptyPowerData() {
        return new OperationalPowerData(
            1500,
            1000,
            900,
            3000,
            3500,
            0.02,
            true,
            "BRAKE_RESISTOR",
            List.of()
        );
    }

    private static OperationalLineData lineDataWithDirectRoute() {
        return lineData(List.of(
            route("R_MAIN", "Main", List.of("SEG-1", "SEG-2")),
            route("R_BRANCH", "Branch", List.of("SEG-1", "SEG-3"))
        ));
    }

    private static OperationalLineData lineDataWithAxleRoute() {
        return lineData(List.of(
            route("R_AXLE", "Axle", List.of("AXLE-1", "AXLE-3"))
        ));
    }

    private static OperationalLineData lineData(List<OperationalLineData.RouteDefinition> routes) {
        return new OperationalLineData(
            "test-line",
            "Test Line",
            List.of(),
            List.of(
                segment("SEG-1", 1, 0, 100, List.of("SEG-2", "SEG-3"), "N1", "N2", "main", 20),
                segment("SEG-2", 2, 100, 200, List.of(), "N2", "N3", "main", 22),
                segment("SEG-3", 3, 100, 200, List.of(), "N2", "N4", "branch", 12)
            ),
            List.of(),
            List.of(),
            List.of(new OperationalLineData.SwitchDefinition(
                "SW-1",
                "SW-1",
                null,
                "NORMAL",
                "SEG-2",
                "SEG-3",
                "N2",
                0,
                null
            )),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            routes
        );
    }

    private static OperationalLineData.TrackSegmentDefinition segment(
        String id,
        int rawId,
        double start,
        double end,
        List<String> forward,
        String from,
        String to,
        String track,
        double speed
    ) {
        return new OperationalLineData.TrackSegmentDefinition(
            id,
            rawId,
            start,
            end,
            end - start,
            speed,
            0,
            0,
            0,
            0,
            forward,
            List.of(),
            from,
            to,
            track
        );
    }

    private static OperationalLineData.RouteDefinition route(String id, String name, List<String> segments) {
        return new OperationalLineData.RouteDefinition(
            id,
            name,
            "MAIN",
            "SIG-" + segments.get(0),
            "SIG-" + segments.get(segments.size() - 1),
            segments,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static TrainState train(String id, double positionMeters) {
        return new TrainEntity(id, "test-line", positionMeters, 20).state();
    }

    private static TrackOccupancy occupancy(TrackService trackService, String segmentId) {
        return trackService.states().stream()
            .filter(segment -> segment.id().equals(segmentId))
            .map(TrackSegmentState::occupancy)
            .findFirst()
            .orElseThrow();
    }

    private record Fixture(
        TrackService trackService,
        RouteInterlockingService interlocking,
        SignalService signalService
    ) {
    }
}
