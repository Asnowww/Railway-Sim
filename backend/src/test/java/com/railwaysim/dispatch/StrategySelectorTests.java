package com.railwaysim.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.disturbance.DisturbanceType;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.strategy.StrategySelector;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategySelectorTests {

    private final StrategySelector selector = new StrategySelector();

    @Test
    void dwellExtendedShortensDwellOnTheDelayedTrainItself() {
        DisturbanceEvent event = new DisturbanceEvent(
            "DIST-1",
            "RUN-1",
            "TR-001",
            "S01",
            DisturbanceType.DWELL_EXTENDED,
            30,
            "OPEN",
            Instant.now(),
            null,
            null
        );

        List<DispatchCommand> commands = selector.select(
            "RUN-1",
            List.of(event),
            List.of(
                profile("TR-001", 400),
                profile("TR-002", 900)
            ),
            new CurrentRunPlan("PLAN-1", "LINE-1", "FLAT", 300, 25, Instant.now())
        );

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).trainId()).isEqualTo("TR-001");
        assertThat(commands.get(0).commandType()).isEqualTo("SHORTEN_DWELL");
    }

    @Test
    void headwayViolationTooShortExtendsDwellOnTheRearTrainThatTriggeredIt() {
        DisturbanceEvent event = new DisturbanceEvent(
            "DIST-2",
            "RUN-1",
            "TR-001",
            null,
            DisturbanceType.HEADWAY_VIOLATION,
            90,
            "OPEN",
            Instant.now(),
            null,
            null,
            "TOO_SHORT",
            300.0,
            120.0,
            90.0,
            90.0
        );

        List<DispatchCommand> commands = selector.select(
            "RUN-1",
            List.of(event),
            List.of(
                profile("TR-001", 400),
                profile("TR-002", 900)
            ),
            new CurrentRunPlan("PLAN-1", "LINE-1", "FLAT", 300, 25, Instant.now())
        );

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).trainId()).isEqualTo("TR-001");
        assertThat(commands.get(0).commandType()).isEqualTo("EXTEND_DWELL");
        assertThat(commands.get(0).payload()).containsEntry("headwayViolationSec", 90.0);
    }

    private static TrainRunProfile profile(String trainId, double positionMeters) {
        return new TrainRunProfile(
            trainId,
            null,
            positionMeters,
            0,
            0.4,
            "DWELLING",
            "S01",
            60,
            25,
            35,
            null,
            0,
            "WAITING_DEPARTURE_DATA",
            "OBSERVE",
            0,
            null
        );
    }
}
