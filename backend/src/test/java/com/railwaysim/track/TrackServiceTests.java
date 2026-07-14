package com.railwaysim.track;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackServiceTests {

    @Test
    void constraintsDoNotTreatSegmentEndAsStationWhenNoNextStationExists() {
        TrackService trackService = trackService(lineWithOnlyStartStation());
        TrainState train = train("TR-1", 394);

        TrackConstraint constraint = trackService.constraintsForTrains(List.of(train)).get(0);

        assertThat(constraint.segmentId()).isEqualTo("T01");
        assertThat(constraint.stationDistanceMeters()).isInfinite();
    }

    @Test
    void constraintsUseRealNextStationDistanceWhenStationExistsAhead() {
        TrackService trackService = trackService(lineWithMidStation());
        TrainState train = train("TR-1", 394);

        TrackConstraint constraint = trackService.constraintsForTrains(List.of(train)).get(0);

        assertThat(constraint.stationDistanceMeters()).isEqualTo(606);
    }

    @Test
    void constraintsKeepCurrentStationVisibleInsideStopWindow() {
        TrackService trackService = trackService(lineWithMidStation());
        TrainState train = train("TR-1", 1004);

        TrackConstraint constraint = trackService.constraintsForTrains(List.of(train)).get(0);

        // 窗口内返回有符号距离：越过停车点 4m → -4（车辆层据此判定已对位/越过）
        assertThat(constraint.stationDistanceMeters()).isEqualTo(-4);
    }

    @Test
    void constraintsDoNotCaptureCurrentStationAfterStopWindow() {
        TrackService trackService = trackService(lineWithMidStation());
        TrainState train = train("TR-1", 1011);

        TrackConstraint constraint = trackService.constraintsForTrains(List.of(train)).get(0);

        assertThat(constraint.stationDistanceMeters()).isInfinite();
    }

    @Test
    void faultMutationsAreIdempotentAndIgnoreUnknownSegments() {
        TrackService trackService = trackService(lineWithMidStation());

        assertThat(trackService.segmentExists("T01")).isTrue();
        assertThat(trackService.injectFault("T01")).isTrue();
        assertThat(trackService.injectFault("T01")).isFalse();
        assertThat(trackService.clearFault("T01")).isTrue();
        assertThat(trackService.clearFault("T01")).isFalse();
        assertThat(trackService.injectFault("UNKNOWN")).isFalse();
        assertThat(trackService.faultSegmentIds()).doesNotContain("UNKNOWN");
    }

    @Test
    void clearingAFaultRestoresActualOccupancy() {
        TrackService trackService = trackService(lineWithMidStation());
        trackService.updateOccupancy(List.of(train("TR-1", 200)));
        trackService.injectFault("T01");

        trackService.clearFault("T01");

        assertThat(occupancy(trackService, "T01")).isEqualTo(TrackOccupancy.OCCUPIED);
    }

    @Test
    void clearingAFaultRestoresAnExistingReservation() {
        TrackService trackService = trackService(lineWithMidStation());
        trackService.applyReservations(java.util.Set.of("T02"));
        trackService.injectFault("T02");

        trackService.clearFault("T02");

        assertThat(occupancy(trackService, "T02")).isEqualTo(TrackOccupancy.RESERVED);
    }

    @Test
    void occupancyUsesAssignedTopologyPathInsteadOfEveryOverlappingMileageRange() {
        TrackService trackService = trackService(lineWithParallelTracks());
        TrainState mainTrain = train("TR-MAIN", 100);
        TrainState depotTrain = train("TR-DEPOT", 100);
        trackService.assignTrainToSegment("TR-MAIN", "T01");
        trackService.assignTrainToSegment("TR-DEPOT", "T11");

        trackService.updateOccupancy(List.of(mainTrain, depotTrain));

        assertThat(trackService.occupyingTrainIds("T01")).containsExactly("TR-MAIN");
        assertThat(trackService.occupyingTrainIds("T11")).containsExactly("TR-DEPOT");
        assertThat(trackService.segmentForTrain(depotTrain).id()).isEqualTo("T11");
    }

    @Test
    void assignedTrainFollowsConnectedSegmentAcrossBoundary() {
        TrackService trackService = trackService(lineWithParallelTracks());
        trackService.assignTrainToSegment("TR-DEPOT", "T11");

        TrainState train = train("TR-DEPOT", 430);
        trackService.updateOccupancy(List.of(train));

        assertThat(trackService.segmentForTrain(train).id()).isEqualTo("T12");
        assertThat(trackService.occupyingTrainIds("T12")).containsExactly("TR-DEPOT");
        assertThat(trackService.occupyingTrainIds("T01")).isEmpty();
    }

    private static TrackService trackService(OperationalLineData lineData) {
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData, emptyPowerData());
        SimulationProperties properties = new SimulationProperties();
        TrackService trackService = new TrackService(catalog, properties);
        trackService.reset();
        return trackService;
    }

    private static OperationalLineData lineWithOnlyStartStation() {
        return new OperationalLineData(
            "branch-demo",
            "Branch Demo",
            List.of(),
            List.of(
                segment("T01", 1, 0, 400, List.of("T02"), "N1", "N2", "main", 20),
                segment("T02", 2, 400, 1000, List.of("T04"), "N2", "N3", "main", 22.2),
                segment("T04", 4, 1000, 1400, List.of(), "N3", "N5", "main", 22.2)
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(new OperationalLineData.StationDefinition("N1_station", "Start", 0, List.of())),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static OperationalLineData lineWithMidStation() {
        return new OperationalLineData(
            "branch-demo",
            "Branch Demo",
            List.of(),
            List.of(
                segment("T01", 1, 0, 400, List.of("T02"), "N1", "N2", "main", 20),
                segment("T02", 2, 400, 1000, List.of("T04"), "N2", "N3", "main", 22.2),
                segment("T04", 4, 1000, 1400, List.of(), "N3", "N5", "main", 22.2)
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new OperationalLineData.StationDefinition("N1_station", "Start", 0, List.of()),
                new OperationalLineData.StationDefinition("N3_station", "Mid", 1000, List.of())
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of()
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
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static TrainState train(String id, double positionMeters) {
        return new TrainEntity(id, "branch-demo", positionMeters, 20).state();
    }

    private static OperationalLineData lineWithParallelTracks() {
        return new OperationalLineData(
            "parallel-demo", "Parallel Demo", List.of(),
            List.of(
                segment("T01", 1, 0, 500, List.of("T02"), "N1", "N2", "main", 20),
                segment("T02", 2, 500, 1000, List.of(), "N2", "N3", "main", 20),
                segment("T11", 11, 0, 420, List.of("T12"), "D1", "D2", "depot", 10),
                segment("T12", 12, 420, 1000, List.of(), "D2", "N2", "depot", 12)
            ),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static TrackOccupancy occupancy(TrackService trackService, String segmentId) {
        return trackService.states().stream()
            .filter(segment -> segment.id().equals(segmentId))
            .map(TrackSegmentState::occupancy)
            .findFirst()
            .orElseThrow();
    }
}
