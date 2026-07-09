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
    }

    private static TrainState train(String id, double positionMeters, String lastDepartureAt) {
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
            8,
            20,
            positionMeters,
            Math.max(0, positionMeters - 20),
            0.35,
            25_200,
            "NORMAL",
            6,
            6,
            "NONE",
            "RUNNING",
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
            null,
            0,
            lastDepartureAt
        );
    }
}
