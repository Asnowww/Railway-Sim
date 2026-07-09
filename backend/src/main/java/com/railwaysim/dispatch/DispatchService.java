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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final List<DispatchCommand> manualCommands = new CopyOnWriteArrayList<>();

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
        manualCommands.clear();
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

    public synchronized void submit(DispatchCommand command) {
        Map<String, Object> payload = command.payload() == null
            ? new HashMap<>()
            : new HashMap<>(command.payload());
        payload.put("simulationRunId", simulationRunId);
        DispatchCommand stored = new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            payload,
            command.reason(),
            command.status(),
            command.createdAt(),
            command.appliedAt()
        );
        manualCommands.add(stored);
        commandRecordStore.save(stored);
        refreshSnapshot();
    }

    public synchronized List<DispatchCommand> pendingCommands() {
        return commandQueue.peekPending();
    }

    public synchronized List<DispatchCommand> drainCommands() {
        return commandQueue.drain();
    }

    public synchronized List<DispatchCommand> drainCommandsOfType(String commandType) {
        List<DispatchCommand> queued = commandQueue.drain();
        if (queued.isEmpty()) {
            return List.of();
        }

        List<DispatchCommand> matched = new ArrayList<>();
        List<DispatchCommand> remaining = new ArrayList<>();
        for (DispatchCommand command : queued) {
            if (commandType.equals(command.commandType())) {
                matched.add(command);
            } else {
                remaining.add(command);
            }
        }
        commandQueue.enqueue(remaining);
        return List.copyOf(matched);
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
            Object disturbanceId = command.payload() == null ? null : command.payload().get("disturbanceId");
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

    public synchronized List<DispatchConstraint> constraintsForTrains(List<TrainState> trains) {
        return constraintsForTrains(trains, true);
    }

    public synchronized List<DispatchConstraint> previewConstraintsForTrains(List<TrainState> trains) {
        return constraintsForTrains(trains, false);
    }

    private List<DispatchConstraint> constraintsForTrains(List<TrainState> trains, boolean consumeQueuedCommands) {
        Map<String, List<DispatchCommand>> commandsByTrain = new HashMap<>();
        for (DispatchCommand command : manualCommands) {
            if (command.trainId() == null || command.trainId().isBlank()) {
                continue;
            }
            commandsByTrain.computeIfAbsent(command.trainId(), ignored -> new ArrayList<>()).add(command);
        }
        for (DispatchCommand command : activeCommands) {
            if (command.trainId() == null || command.trainId().isBlank()) {
                continue;
            }
            if (!CommandStatus.PENDING.equals(command.status()) && !CommandStatus.APPLIED.equals(command.status())) {
                continue;
            }
            commandsByTrain.computeIfAbsent(command.trainId(), ignored -> new ArrayList<>()).add(command);
        }

        List<DispatchConstraint> constraints = trains.stream()
            .map(train -> constraintForTrain(train, commandsByTrain.getOrDefault(train.id(), List.of())))
            .toList();

        if (consumeQueuedCommands) {
            List<DispatchCommand> consumed = commandQueue.peekPending().stream()
                .filter(command -> !"REROUTE".equals(command.commandType()))
                .toList();
            if (!consumed.isEmpty()) {
                drainQueuedCommands(consumed);
            }
        }
        return constraints;
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

    private void drainQueuedCommands(List<DispatchCommand> consumed) {
        List<DispatchCommand> queued = commandQueue.drain();
        List<DispatchCommand> remaining = queued.stream()
            .filter(command -> consumed.stream().noneMatch(done -> done.id().equals(command.id())))
            .toList();
        commandQueue.enqueue(remaining);
    }

    private DispatchConstraint constraintForTrain(TrainState train, List<DispatchCommand> commands) {
        if (commands.isEmpty()) {
            return DispatchConstraint.none(train.id());
        }

        boolean holdTrain = false;
        double speedFactor = 1.0;
        Double targetSpeed = null;
        boolean releaseStationStop = false;
        List<String> reasons = new ArrayList<>();

        for (DispatchCommand command : commands) {
            switch (command.commandType()) {
                case "HOLD", "HOLD_TRAIN" -> {
                    holdTrain = true;
                    reasons.add(reason(command));
                }
                case "EXTEND_DWELL", "SHORTEN_DWELL" -> {
                    int delta = payloadInt(command, "deltaDwellSec", 0);
                    int targetDwell = adjustedDwellTarget(delta);
                    if (isDwelling(train)) {
                        if (delta >= 0 && train.dwellElapsedSeconds() < targetDwell) {
                            holdTrain = true;
                        } else if (delta < 0 && train.dwellElapsedSeconds() >= targetDwell) {
                            releaseStationStop = true;
                        }
                    }
                    reasons.add(reason(command) + "(targetDwell=" + targetDwell + "s)");
                }
                case "HEADWAY_ADJUST" -> {
                    int targetHeadway = payloadInt(command, "targetHeadwaySec", currentPlan.departureIntervalSec());
                    if (targetHeadway < currentPlan.departureIntervalSec()) {
                        if (isDwelling(train) && train.dwellElapsedSeconds() >= properties.getMinDwellSec()) {
                            releaseStationStop = true;
                        }
                    } else if (targetHeadway > currentPlan.departureIntervalSec()) {
                        if (isDwelling(train) && train.dwellElapsedSeconds() < properties.getMaxDwellSec()) {
                            holdTrain = true;
                        } else {
                            speedFactor = Math.min(speedFactor, 0.9);
                        }
                    }
                    reasons.add(reason(command) + "(targetHeadway=" + targetHeadway + "s)");
                }
                case "SPEED_FACTOR", "LIMIT_FACTOR" -> {
                    speedFactor = Math.min(speedFactor, payloadDouble(command, "detail", 1.0));
                    reasons.add(reason(command));
                }
                case "SPEED_LIMIT", "TEMP_SPEED_LIMIT" -> {
                    double parsedLimit = payloadDouble(command, "detail", Double.NaN);
                    if (!Double.isNaN(parsedLimit)) {
                        targetSpeed = targetSpeed == null ? parsedLimit : Math.min(targetSpeed, parsedLimit);
                    }
                    reasons.add(reason(command));
                }
                case "SPEED_BIAS" -> {
                    double ratio = payloadDouble(command, "speedBiasRatio", 1.0);
                    if (ratio > 1.0 && isDwelling(train) && train.dwellElapsedSeconds() >= properties.getMinDwellSec()) {
                        releaseStationStop = true;
                    } else {
                        speedFactor = Math.min(speedFactor, ratio);
                    }
                    reasons.add(reason(command));
                }
                default -> reasons.add(reason(command));
            }
        }
        return new DispatchConstraint(
            train.id(),
            holdTrain,
            Math.max(0, Math.min(1, speedFactor)),
            targetSpeed,
            releaseStationStop && !holdTrain,
            String.join("; ", reasons)
        );
    }

    private boolean isDwelling(TrainState train) {
        return "DWELLING".equals(train.status())
            || (train.currentStationId() != null && !train.currentStationId().isBlank())
            || train.dwellElapsedSeconds() > 0;
    }

    private int adjustedDwellTarget(int deltaSeconds) {
        int target = currentPlan.defaultDwellTimeSec() + deltaSeconds;
        return Math.max(properties.getMinDwellSec(), Math.min(properties.getMaxDwellSec(), target));
    }

    private String reason(DispatchCommand command) {
        String detail = payloadString(command, "detail");
        if (detail == null || detail.isBlank()) {
            return command.commandType();
        }
        return command.commandType() + ":" + detail;
    }

    private double payloadDouble(DispatchCommand command, String key, double fallback) {
        if (command.payload() == null) {
            return fallback;
        }
        Object value = command.payload().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int payloadInt(DispatchCommand command, String key, int fallback) {
        return (int) Math.round(payloadDouble(command, key, fallback));
    }

    private String payloadString(DispatchCommand command, String key) {
        if (command.payload() == null) {
            return null;
        }
        Object value = command.payload().get(key);
        return value == null ? null : value.toString();
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
