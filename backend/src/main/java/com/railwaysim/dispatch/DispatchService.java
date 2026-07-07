package com.railwaysim.dispatch;

import com.railwaysim.dispatch.command.CommandQueue;
import com.railwaysim.dispatch.command.CommandRecordStore;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.command.CommandValidator;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.disturbance.DisturbanceDetector;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.disturbance.DisturbanceRecordStore;
import com.railwaysim.dispatch.monitor.InMemoryStationRecordStore;
import com.railwaysim.dispatch.monitor.TrainRunMonitor;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.strategy.StrategySelector;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DispatchService {

    private final OperationPlanLoader planLoader;
    private final DispatchProperties properties;
    private final TrainRunMonitor trainRunMonitor;
    private final DisturbanceDetector disturbanceDetector;
    private final StrategySelector strategySelector;
    private final CommandValidator commandValidator;
    private final CommandQueue commandQueue;
    private final DisturbanceRecordStore disturbanceRecordStore;
    private final CommandRecordStore commandRecordStore;
    private final InMemoryStationRecordStore stationRecordStore;

    private String simulationRunId = UUID.randomUUID().toString();
    private Instant simulationStart = Instant.now();
    private Instant lastEvaluatedAt = Instant.EPOCH;
    private CurrentRunPlan currentPlan;
    private List<TrainRunProfile> latestProfiles = List.of();
    private List<DispatchCommand> activeCommands = List.of();
    private DispatchSnapshot latestSnapshot = DispatchSnapshot.empty();

    public DispatchService(
        OperationPlanLoader planLoader,
        DispatchProperties properties,
        TrainRunMonitor trainRunMonitor,
        DisturbanceDetector disturbanceDetector,
        StrategySelector strategySelector,
        CommandValidator commandValidator,
        CommandQueue commandQueue,
        DisturbanceRecordStore disturbanceRecordStore,
        CommandRecordStore commandRecordStore,
        InMemoryStationRecordStore stationRecordStore
    ) {
        this.planLoader = planLoader;
        this.properties = properties;
        this.trainRunMonitor = trainRunMonitor;
        this.disturbanceDetector = disturbanceDetector;
        this.strategySelector = strategySelector;
        this.commandValidator = commandValidator;
        this.commandQueue = commandQueue;
        this.disturbanceRecordStore = disturbanceRecordStore;
        this.commandRecordStore = commandRecordStore;
        this.stationRecordStore = stationRecordStore;
        this.currentPlan = planLoader.resolve(Instant.now());
        this.latestSnapshot = buildSnapshot(currentPlan, latestProfiles, List.of(), List.of());
    }

    public synchronized void reset() {
        simulationRunId = UUID.randomUUID().toString();
        simulationStart = Instant.now();
        lastEvaluatedAt = Instant.EPOCH;
        currentPlan = planLoader.resolve(simulationStart);
        latestProfiles = List.of();
        activeCommands = List.of();
        commandQueue.clear();
        disturbanceDetector.reset();
        trainRunMonitor.reset(simulationStart);
        stationRecordStore.clear();
        if (commandRecordStore instanceof com.railwaysim.dispatch.command.InMemoryCommandRecordStore store) {
            store.clear();
        }
        if (disturbanceRecordStore instanceof com.railwaysim.dispatch.disturbance.InMemoryDisturbanceRecordStore store) {
            store.clear();
        }
        latestSnapshot = buildSnapshot(currentPlan, latestProfiles, List.of(), List.of());
    }

    public synchronized List<DispatchCommand> drainCommands() {
        return commandQueue.drain();
    }

    public synchronized void markCommandsApplied(List<DispatchCommand> appliedCommands) {
        List<DispatchCommand> updated = new ArrayList<>();
        Instant now = Instant.now();
        for (DispatchCommand command : appliedCommands) {
            if (CommandStatus.SKIPPED.equals(command.status())) {
                commandRecordStore.save(command);
                updated.add(command);
                continue;
            }
            DispatchCommand applied = new DispatchCommand(
                command.id(),
                command.trainId(),
                command.commandType(),
                command.payload(),
                command.reason(),
                CommandStatus.APPLIED,
                command.createdAt(),
                now
            );
            commandRecordStore.update(applied);
            updated.add(applied);
            Object disturbanceId = command.payload().get("disturbanceId");
            if (disturbanceId != null) {
                disturbanceDetector.attachCommand(disturbanceId.toString(), command.id());
            }
        }
        activeCommands = mergeActiveCommands(updated);
        refreshSnapshot();
    }

    public synchronized void evaluate(
        TickContext context,
        List<TrainState> trains,
        List<MovementAuthority> authorities
    ) {
        if (!shouldEvaluate(context.simulatedTime())) {
            refreshSnapshot();
            return;
        }
        lastEvaluatedAt = context.simulatedTime();
        currentPlan = planLoader.resolve(context.simulatedTime());
        latestProfiles = trainRunMonitor.update(simulationRunId, context.simulatedTime(), currentPlan, trains);

        List<DisturbanceEvent> created = disturbanceDetector.detect(
            simulationRunId,
            context.simulatedTime(),
            currentPlan,
            latestProfiles
        );
        for (DisturbanceEvent event : created) {
            disturbanceRecordStore.save(event);
        }

        List<DisturbanceEvent> openEvents = disturbanceDetector.openEvents().stream()
            .filter(event -> "OPEN".equals(event.status()))
            .toList();
        List<DispatchCommand> generated = strategySelector.select(
            simulationRunId,
            openEvents,
            latestProfiles,
            currentPlan
        );
        List<DispatchCommand> validated = commandValidator.validate(generated, authorities);
        commandQueue.enqueue(validated);
        for (DispatchCommand command : validated) {
            commandRecordStore.save(command);
        }
        activeCommands = mergeActiveCommands(validated);
        refreshSnapshot();
    }

    public synchronized DispatchSnapshot snapshot() {
        return latestSnapshot;
    }

    public synchronized CurrentRunPlan currentPlan() {
        return currentPlan;
    }

    public synchronized String simulationRunId() {
        return simulationRunId;
    }

    public synchronized List<DisturbanceEvent> disturbances() {
        return disturbanceRecordStore.list(simulationRunId);
    }

    public synchronized List<DispatchCommand> commands() {
        return commandRecordStore.list(simulationRunId);
    }

    private boolean shouldEvaluate(Instant simulatedTime) {
        if (lastEvaluatedAt.equals(Instant.EPOCH)) {
            return true;
        }
        long elapsedMs = simulatedTime.toEpochMilli() - lastEvaluatedAt.toEpochMilli();
        return elapsedMs >= properties.getEvaluateIntervalMs();
    }

    private void refreshSnapshot() {
        latestSnapshot = buildSnapshot(
            currentPlan,
            latestProfiles,
            disturbanceDetector.openEvents(),
            activeCommands
        );
    }

    private DispatchSnapshot buildSnapshot(
        CurrentRunPlan plan,
        List<TrainRunProfile> profiles,
        List<DisturbanceEvent> disturbances,
        List<DispatchCommand> commands
    ) {
        List<DispatchSnapshot.TrainProfileView> trainViews = profiles.stream()
            .map(profile -> new DispatchSnapshot.TrainProfileView(
                profile.trainId(),
                profile.headwayActualSec(),
                profile.headwayDeviationSec(),
                profile.dwellDeviationSec()
            ))
            .toList();
        List<DispatchSnapshot.DisturbanceView> disturbanceViews = disturbances.stream()
            .filter(event -> "OPEN".equals(event.status()) || "HANDLED".equals(event.status()))
            .map(event -> new DispatchSnapshot.DisturbanceView(
                event.id(),
                event.trainId(),
                event.stationId(),
                event.disturbanceType().name(),
                event.deviationValue(),
                event.status()
            ))
            .toList();
        List<DispatchSnapshot.CommandView> commandViews = commands.stream()
            .map(command -> new DispatchSnapshot.CommandView(
                command.id(),
                command.trainId(),
                command.commandType(),
                command.status(),
                command.reason()
            ))
            .toList();
        boolean interventionActive = !disturbanceViews.isEmpty() || commandViews.stream()
            .anyMatch(command -> CommandStatus.PENDING.equals(command.status()) || CommandStatus.APPLIED.equals(command.status()));
        return new DispatchSnapshot(
            plan.periodType(),
            plan.planId(),
            plan.departureIntervalSec(),
            plan.defaultDwellTimeSec(),
            interventionActive,
            trainViews,
            disturbanceViews,
            commandViews
        );
    }

    private List<DispatchCommand> mergeActiveCommands(List<DispatchCommand> incoming) {
        List<DispatchCommand> merged = new ArrayList<>(activeCommands);
        for (DispatchCommand command : incoming) {
            merged.removeIf(existing -> existing.id().equals(command.id()));
            merged.add(command);
        }
        return merged.stream()
            .filter(command -> CommandStatus.PENDING.equals(command.status()) || CommandStatus.APPLIED.equals(command.status()))
            .toList();
    }
}
