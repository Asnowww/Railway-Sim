package com.railwaysim.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchServiceTests {

    private final DispatchService dispatchService = new DispatchService();

    @Test
    void holdCommandConsumesOnceAndForcesZeroSpeedLimit() {
        dispatchService.submit(command("TR-1", "HOLD", null));

        List<DispatchConstraint> constraints = dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(constraints).hasSize(1);
        DispatchConstraint constraint = constraints.get(0);
        assertThat(constraint.holdTrain()).isTrue();
        assertThat(constraint.applyToSpeedLimit(20)).isZero();
        assertThat(dispatchService.pendingCommands()).isEmpty();
    }

    @Test
    void previewConstraintsDoesNotConsumePendingCommands() {
        dispatchService.submit(command("TR-1", "SPEED_LIMIT", "8"));

        List<DispatchConstraint> preview = dispatchService.previewConstraintsForTrains(List.of(train("TR-1")));

        assertThat(preview.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
        assertThat(dispatchService.pendingCommands()).hasSize(1);

        dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(dispatchService.pendingCommands()).isEmpty();
    }

    private static DispatchCommand command(String trainId, String type, String detail) {
        return new DispatchCommand("DC-test-" + type, trainId, type, detail, Instant.now());
    }

    private static TrainState train(String id) {
        return new TrainEntity(id, "test-line", 50, 20).state();
    }
}
