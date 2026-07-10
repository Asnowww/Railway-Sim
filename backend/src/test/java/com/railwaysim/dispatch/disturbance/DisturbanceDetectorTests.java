package com.railwaysim.dispatch.disturbance;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisturbanceDetectorTests {

    private final DispatchProperties properties = properties();
    private final CurrentRunPlan plan = new CurrentRunPlan("P1", "L1", "FLAT", 300, 25, Instant.EPOCH);

    @Test
    void headwayShrinkRecoversAtShrinkThresholdInsteadOfExtremeValue() {
        DisturbanceDetector detector = new DisturbanceDetector(properties);

        detector.detect("RUN-1", Instant.EPOCH, plan, List.of(profile("TR-1", 100)));
        assertThat(detector.openEvents()).hasSize(1);

        detector.detect("RUN-1", Instant.EPOCH.plusSeconds(1), plan, List.of(profile("TR-1", 220)));

        assertThat(detector.openEvents()).isEmpty();
    }

    @Test
    void headwayExpandRecoversAtExpandThresholdInsteadOfExtremeValue() {
        DisturbanceDetector detector = new DisturbanceDetector(properties);

        detector.detect("RUN-1", Instant.EPOCH, plan, List.of(profile("TR-1", 600)));
        assertThat(detector.openEvents()).hasSize(1);

        detector.detect("RUN-1", Instant.EPOCH.plusSeconds(1), plan, List.of(profile("TR-1", 440)));

        assertThat(detector.openEvents()).isEmpty();
    }

    @Test
    void departureDelayCreatesConfiguredDisturbance() {
        DisturbanceDetector detector = new DisturbanceDetector(properties);

        List<DisturbanceEvent> created = detector.detect(
            "RUN-1",
            Instant.EPOCH,
            plan,
            List.of(profile("TR-1", 300, 31))
        );

        assertThat(created)
            .extracting(DisturbanceEvent::disturbanceType)
            .containsExactly(DisturbanceType.DEPARTURE_DELAY);
        assertThat(detector.openEvents()).hasSize(1);
    }

    private static DispatchProperties properties() {
        DispatchProperties properties = new DispatchProperties();
        properties.setConfirmTicks(1);
        properties.setCooldownSec(0);
        return properties;
    }

    private static TrainRunProfile profile(String trainId, double headwayActualSec) {
        return profile(trainId, headwayActualSec, 0);
    }

    private static TrainRunProfile profile(String trainId, double headwayActualSec, int departureDelaySec) {
        return new TrainRunProfile(
            trainId,
            "TR-FRONT",
            100,
            0,
            0.35,
            "RUNNING",
            null,
            0,
            25,
            0,
            headwayActualSec,
            0,
            "ON_TARGET",
            "NONE",
            departureDelaySec,
            null
        );
    }
}
