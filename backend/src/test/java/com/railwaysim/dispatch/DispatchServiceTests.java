package com.railwaysim.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.dispatch.command.CommandQueue;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.command.CommandValidator;
import com.railwaysim.dispatch.command.InMemoryCommandRecordStore;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.disturbance.DisturbanceDetector;
import com.railwaysim.dispatch.disturbance.InMemoryDisturbanceRecordStore;
import com.railwaysim.dispatch.monitor.InMemoryStationRecordStore;
import com.railwaysim.dispatch.monitor.TrainRunMonitor;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.PlannedScheduleCalculator;
import com.railwaysim.dispatch.strategy.StrategySelector;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class DispatchServiceTests {

    private final DispatchService dispatchService = dispatchService();

    @Test
    void submittedHoldCommandForcesZeroSpeedLimit() {
        dispatchService.submit(command("TR-1", "HOLD", null));

        List<DispatchConstraint> constraints = dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(constraints).hasSize(1);
        DispatchConstraint constraint = constraints.get(0);
        assertThat(constraint.holdTrain()).isTrue();
        assertThat(constraint.applyToSpeedLimit(20)).isZero();
    }

    @Test
    void manualSpeedLimitCommandProjectsToPreviewAndApplyPaths() {
        dispatchService.submit(command("TR-1", "SPEED_LIMIT", "8"));

        List<DispatchConstraint> preview = dispatchService.previewConstraintsForTrains(List.of(train("TR-1")));

        assertThat(preview.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
        assertThat(dispatchService.pendingCommands()).isEmpty();
        assertThat(dispatchService.commands()).hasSize(1);

        List<DispatchConstraint> applied = dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(applied.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
    }

    private static DispatchCommand command(String trainId, String type, String detail) {
        Map<String, Object> payload = detail == null ? Map.of() : Map.of("detail", detail);
        return new DispatchCommand(
            "DC-test-" + type,
            trainId,
            type,
            payload,
            "test",
            CommandStatus.PENDING,
            Instant.now(),
            null
        );
    }

    private static TrainState train(String id) {
        return new TrainEntity(id, "test-line", 50, 20).state();
    }

    private static DispatchService dispatchService() {
        try {
            DispatchProperties properties = new DispatchProperties();
            OperationPlanLoader planLoader = new OperationPlanLoader(properties, new DefaultResourceLoader());
            planLoader.load();
            InMemoryStationRecordStore stationStore = new InMemoryStationRecordStore();
            return new DispatchService(
                planLoader,
                properties,
                new TrainRunMonitor(planLoader, new PlannedScheduleCalculator(properties), stationStore),
                new DisturbanceDetector(properties),
                new StrategySelector(),
                new CommandValidator(),
                new CommandQueue(),
                new InMemoryDisturbanceRecordStore(),
                new InMemoryCommandRecordStore(),
                stationStore
            );
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load dispatch test plan", ex);
        }
    }
}
