package com.railwaysim.dispatch.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.PlannedScheduleCalculator;
import com.railwaysim.train.TrainState;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class TrainRunMonitorTests {

    @Test
    void followingTrainHeadwayIsMeasuredAgainstTheTrainAhead() throws IOException {
        DispatchProperties properties = new DispatchProperties();
        OperationPlanLoader planLoader = new OperationPlanLoader(properties, new DefaultResourceLoader());
        planLoader.load();
        TrainRunMonitor monitor = new TrainRunMonitor(
            planLoader,
            new PlannedScheduleCalculator(properties),
            new InMemoryStationRecordStore()
        );
        CurrentRunPlan plan = new CurrentRunPlan("PLAN-1", "LINE-1", "FLAT", 300, 25, Instant.EPOCH);

        List<TrainRunProfile> profiles = monitor.update(
            "RUN-1",
            Instant.parse("2026-07-09T00:20:00Z"),
            plan,
            List.of(
                train("TR-001", 400, "2026-07-09T00:05:00Z"),
                train("TR-002", 900, "2026-07-09T00:00:00Z")
            )
        );

        TrainRunProfile rearTrain = profiles.stream()
            .filter(profile -> profile.trainId().equals("TR-001"))
            .findFirst()
            .orElseThrow();
        TrainRunProfile frontTrain = profiles.stream()
            .filter(profile -> profile.trainId().equals("TR-002"))
            .findFirst()
            .orElseThrow();

        assertThat(frontTrain.frontTrainId()).isNull();
        assertThat(frontTrain.headwayActualSec()).isNull();
        assertThat(rearTrain.frontTrainId()).isEqualTo("TR-002");
        assertThat(rearTrain.headwayActualSec()).isEqualTo(300);
        assertThat(rearTrain.headwayState()).isEqualTo("ON_TARGET");
        assertThat(rearTrain.headwayAction()).isEqualTo("NORMAL");
    }

    @Test
    void departureEventUpdatesLatestDepartureDelayInRunProfile() throws IOException {
        DispatchProperties properties = new DispatchProperties();
        OperationPlanLoader planLoader = new OperationPlanLoader(properties, new DefaultResourceLoader());
        planLoader.load();
        InMemoryStationRecordStore stationStore = new InMemoryStationRecordStore();
        TrainRunMonitor monitor = new TrainRunMonitor(
            planLoader,
            new PlannedScheduleCalculator(properties),
            stationStore
        );
        Instant simulationStart = Instant.parse("2026-07-09T09:00:00Z");
        CurrentRunPlan plan = new CurrentRunPlan("PLAN-1", "LINE-1", "FLAT", 300, 25, simulationStart);
        monitor.reset(simulationStart);

        monitor.update("RUN-1", simulationStart, plan, List.of(
            trainState("TR-001", 0, 0, "DWELLING", "S01", 10, null)
        ));
        List<TrainRunProfile> profiles = monitor.update("RUN-1", simulationStart.plusSeconds(60), plan, List.of(
            trainState("TR-001", 10, 4, "RUNNING", null, 0, simulationStart.plusSeconds(60).toString())
        ));

        TrainStationEvent departure = stationStore.list("RUN-1").stream()
            .filter(event -> event.eventType() == TrainStationEvent.EventType.DEPARTURE)
            .findFirst()
            .orElseThrow();
        assertThat(departure.delaySec()).isEqualTo(35);
        assertThat(profiles.get(0).departureDelaySec()).isEqualTo(departure.delaySec());
    }

    @Test
    void headwayIsNotComparedWhenAdjacentTrainsDepartedFromDifferentStations() throws IOException {
        DispatchProperties properties = new DispatchProperties();
        OperationPlanLoader planLoader = new OperationPlanLoader(properties, new DefaultResourceLoader());
        planLoader.load();
        TrainRunMonitor monitor = new TrainRunMonitor(
            planLoader,
            new PlannedScheduleCalculator(properties),
            new InMemoryStationRecordStore()
        );
        Instant start = Instant.parse("2026-07-09T09:00:00Z");
        CurrentRunPlan plan = new CurrentRunPlan("PLAN-1", "LINE-1", "FLAT", 300, 25, start);
        monitor.reset(start);

        monitor.update("RUN-1", start, plan, List.of(
            trainState("TR-001", 400, 0, "DWELLING", "S01", 25, null),
            trainState("TR-002", 900, 0, "DWELLING", "S02", 25, null)
        ));
        List<TrainRunProfile> profiles = monitor.update("RUN-1", start.plusSeconds(60), plan, List.of(
            trainState("TR-001", 450, 8, "RUNNING", null, 0, start.plusSeconds(60).toString()),
            trainState("TR-002", 950, 8, "RUNNING", null, 0, start.plusSeconds(60).toString())
        ));

        TrainRunProfile rearTrain = profiles.stream()
            .filter(profile -> profile.trainId().equals("TR-001"))
            .findFirst()
            .orElseThrow();
        assertThat(rearTrain.frontTrainId()).isEqualTo("TR-002");
        assertThat(rearTrain.headwayActualSec()).isNull();
        assertThat(rearTrain.headwayState()).isEqualTo("WAITING_DEPARTURE_DATA");
    }

    private static TrainState train(String id, double positionMeters, String lastDepartureAt) {
        return trainState(id, positionMeters, 8, "RUNNING", null, 0, lastDepartureAt);
    }

    private static TrainState trainState(
        String id,
        double positionMeters,
        double speedMetersPerSecond,
        String status,
        String currentStationId,
        int dwellElapsedSeconds,
        String lastDepartureAt
    ) {
        return new TrainState(
            id,
            "test-line",
            id,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "TEST",
            0,
            "UNKNOWN",
            positionMeters,
            speedMetersPerSecond,
            20,
            positionMeters,
            Math.max(0, positionMeters - 20),
            0.35,
            25_200,
            "NORMAL",
            6,
            6,
            "NONE",
            status,
            "ATO",
            false,
            "CLOSED_LOCKED",
            "APPLYING",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "ACCELERATING",
            "SPEED_MARGIN_AVAILABLE",
            15,
            0,
            300,
            1_000,
            100,
            0.3,
            20_000,
            0,
            0,
            100,
            50_000,
            0,
            0,
            0,
            "OK",
            currentStationId,
            dwellElapsedSeconds,
            lastDepartureAt
        );
    }
}
