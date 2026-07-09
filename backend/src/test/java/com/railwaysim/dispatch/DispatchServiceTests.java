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
    void manualSpeedLimitCommandProjectsToPreviewAndApplyPaths() {
        dispatchService.submit(command("TR-1", "SPEED_LIMIT", "8"));

        List<DispatchConstraint> preview = dispatchService.previewConstraintsForTrains(List.of(train("TR-1")));

        assertThat(preview.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
        assertThat(dispatchService.pendingCommands()).isEmpty();
        assertThat(dispatchService.commands()).hasSize(1);

        List<DispatchConstraint> applied = dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(applied.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
    }

    @Test
    void extendDwellCommandHoldsDwellingTrainUntilAdjustedTarget() {
        dispatchService.submit(commandWithPayload("TR-1", "EXTEND_DWELL", Map.of("deltaDwellSec", 10)));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 20))).get(0);

        assertThat(constraint.holdTrain()).isTrue();
        assertThat(constraint.releaseStationStop()).isFalse();
        assertThat(constraint.applyToSpeedLimit(20)).isZero();
    }

    @Test
    void defaultDwellTargetReleasesDwellingTrainAfterPlannedStop() {
        int targetDwell = dispatchService.currentPlan().defaultDwellTimeSec();

        DispatchConstraint holding = dispatchService.previewConstraintsForTrains(
            List.of(dwellingTrain("TR-1", Math.max(0, targetDwell - 1)))
        ).get(0);
        DispatchConstraint released = dispatchService.previewConstraintsForTrains(
            List.of(dwellingTrain("TR-1", targetDwell))
        ).get(0);

        assertThat(holding.holdTrain()).isTrue();
        assertThat(holding.releaseStationStop()).isFalse();
        assertThat(released.holdTrain()).isFalse();
        assertThat(released.releaseStationStop()).isTrue();
    }

    @Test
    void shortenDwellCommandReleasesStationStopAfterAdjustedTarget() {
        dispatchService.submit(commandWithPayload("TR-1", "SHORTEN_DWELL", Map.of("deltaDwellSec", -5)));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 20))).get(0);

        assertThat(constraint.holdTrain()).isFalse();
        assertThat(constraint.releaseStationStop()).isTrue();
        assertThat(constraint.applyToSpeedLimit(20)).isEqualTo(20);
    }

    @Test
    void headwayAdjustCommandCanReleaseMinimumDwellForShorterHeadway() {
        int shorterHeadway = Math.max(1, dispatchService.currentPlan().departureIntervalSec() - 1);
        dispatchService.submit(commandWithPayload("TR-1", "HEADWAY_ADJUST", Map.of("targetHeadwaySec", shorterHeadway)));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 15))).get(0);

        assertThat(constraint.releaseStationStop()).isTrue();
    }

    private static DispatchCommand command(String trainId, String type, String detail) {
        Map<String, Object> payload = detail == null ? Map.of() : Map.of("detail", detail);
        return commandWithPayload(trainId, type, payload);
    }

    private static DispatchCommand commandWithPayload(String trainId, String type, Map<String, Object> payload) {
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

    private static TrainState dwellingTrain(String id, int dwellElapsedSeconds) {
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
            50,
            0,
            20,
            50,
            30,
            0.35,
            25_200,
            "NORMAL",
            6,
            6,
            "NONE",
            "DWELLING",
            "STATION_CONTROL",
            true,
            "CLOSED_LOCKED",
            "IDLE",
            "APPLYING",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "STATION_STOPPED",
            "TEST",
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK",
            "S01",
            dwellElapsedSeconds,
            null
        );
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
