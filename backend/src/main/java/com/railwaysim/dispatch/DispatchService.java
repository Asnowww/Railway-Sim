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
import com.railwaysim.dispatch.monitor.StationInfo;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if ("REROUTE".equals(stored.commandType()) || "REQUEST_ROUTE".equals(stored.commandType())) {
            commandQueue.enqueue(List.of(stored));
        }
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

    public synchronized void markCommandsSent(List<DispatchCommand> sentCommands) {
        List<DispatchCommand> updated = new ArrayList<>();
        for (DispatchCommand command : sentCommands) {
            if (CommandStatus.SKIPPED.equals(command.status())) {
                commandRecordStore.save(command);
                updated.add(command);
                continue;
            }
            Map<String, Object> payload = command.payload() == null
                ? new HashMap<>()
                : new HashMap<>(command.payload());
            payload.putIfAbsent("simulationRunId", simulationRunId);
            DispatchCommand sent = new DispatchCommand(
                command.id(),
                command.trainId(),
                command.commandType(),
                payload,
                command.reason(),
                CommandStatus.SENT,
                command.createdAt(),
                command.appliedAt()
            );
            commandRecordStore.update(sent);
            replaceManualCommand(sent);
            updated.add(sent);
            Object disturbanceId = payload.get("disturbanceId");
            if (disturbanceId != null) {
                disturbanceDetector.attachCommand(disturbanceId.toString(), command.id());
            }
        }
        syncDisturbanceRecords();
        activeCommands = mergeActiveCommands(updated);
        refreshSnapshot();
    }

    public synchronized void markCommandsApplied(List<DispatchCommand> appliedCommands) {
        markCommandsSent(appliedCommands);
    }

    public synchronized void acceptFeedback(List<DispatchCommandFeedback> feedbacks) {
        if (feedbacks == null || feedbacks.isEmpty()) {
            return;
        }
        Map<String, DispatchCommand> commandById = new HashMap<>();
        for (DispatchCommand command : commandRecordStore.list(simulationRunId)) {
            commandById.put(command.id(), command);
        }

        List<DispatchCommand> updated = new ArrayList<>();
        for (DispatchCommandFeedback feedback : feedbacks) {
            if (feedback.commandId() == null || feedback.commandId().isBlank()) {
                continue;
            }
            DispatchCommand current = commandById.get(feedback.commandId());
            if (current == null) {
                continue;
            }
            String nextStatus = nextStatusFromFeedback(current.status(), feedback.feedbackStatus());
            if (nextStatus == null || (
                nextStatus.equals(current.status())
                    && !shouldRecordFeedback(current.status(), feedback.feedbackStatus())
            )) {
                continue;
            }
            DispatchCommand progressed = commandWithFeedback(current, nextStatus, feedback);
            commandRecordStore.update(progressed);
            replaceManualCommand(progressed);
            commandById.put(progressed.id(), progressed);
            updated.add(progressed);
        }
        if (!updated.isEmpty()) {
            activeCommands = mergeActiveCommands(updated);
            refreshSnapshot();
        }
    }

    public synchronized void cancelCommand(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return;
        }
        for (DispatchCommand command : commandRecordStore.list(simulationRunId)) {
            if (!command.id().equals(commandId)) {
                continue;
            }
            DispatchCommand cancelled = commandWithStatus(command, CommandStatus.CANCELLED, Instant.now());
            commandRecordStore.update(cancelled);
            replaceManualCommand(cancelled);
            activeCommands = mergeActiveCommands(List.of(cancelled));
            refreshSnapshot();
            return;
        }
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
        syncDisturbanceRecords();
        progressActiveCommands(context.simulatedTime(), trains, authorities, latestProfiles);

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

    private void progressActiveCommands(
        Instant simulatedAt,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<TrainRunProfile> profiles
    ) {
        if (activeCommands.isEmpty()) {
            return;
        }
        Map<String, TrainState> trainById = new HashMap<>();
        for (TrainState train : trains) {
            trainById.put(train.id(), train);
        }
        Map<String, MovementAuthority> authorityByTrain = new HashMap<>();
        for (MovementAuthority authority : authorities) {
            authorityByTrain.put(authority.trainId(), authority);
        }
        Map<String, TrainRunProfile> profileByTrain = new HashMap<>();
        for (TrainRunProfile profile : profiles) {
            profileByTrain.put(profile.trainId(), profile);
        }
        Map<String, DisturbanceEvent> disturbanceById = new HashMap<>();
        for (DisturbanceEvent event : disturbanceDetector.events()) {
            disturbanceById.put(event.id(), event);
        }

        List<DispatchCommand> updated = new ArrayList<>();
        for (DispatchCommand command : activeCommands) {
            DispatchCommand progressed = progressCommand(
                command,
                simulatedAt,
                trainById.get(command.trainId()),
                authorityByTrain.get(command.trainId()),
                profileByTrain.get(command.trainId()),
                disturbanceById
            );
            if (!progressed.equals(command)) {
                commandRecordStore.update(progressed);
                replaceManualCommand(progressed);
            }
            updated.add(progressed);
        }
        activeCommands = mergeActiveCommands(updated);
    }

    private DispatchCommand progressCommand(
        DispatchCommand command,
        Instant simulatedAt,
        TrainState train,
        MovementAuthority authority,
        TrainRunProfile profile,
        Map<String, DisturbanceEvent> disturbanceById
    ) {
        if (!isEffectTrackedStatus(command.status())) {
            return command;
        }
        if (isDisturbanceRecovered(command, disturbanceById)) {
            return commandWithStatus(command, CommandStatus.EFFECT_CONFIRMED, simulatedAt);
        }
        if (CommandStatus.SENT.equals(command.status()) && isAppliedObserved(command, train, authority, profile)) {
            return commandWithStatus(command, CommandStatus.APPLIED, simulatedAt);
        }
        if (CommandStatus.APPLIED.equals(command.status()) && isEffectConfirmed(command, train, profile)) {
            return commandWithStatus(command, CommandStatus.EFFECT_CONFIRMED, simulatedAt);
        }
        if (isTimedOut(command, simulatedAt)) {
            return commandWithStatus(command, CommandStatus.TIMEOUT, simulatedAt);
        }
        return command;
    }

    private boolean isEffectTrackedStatus(String status) {
        return CommandStatus.PENDING.equals(status)
            || CommandStatus.SENT.equals(status)
            || CommandStatus.APPLIED.equals(status);
    }

    private boolean isDisturbanceRecovered(DispatchCommand command, Map<String, DisturbanceEvent> disturbanceById) {
        String disturbanceId = payloadString(command, "disturbanceId");
        if (disturbanceId == null || disturbanceId.isBlank()) {
            return false;
        }
        DisturbanceEvent event = disturbanceById.get(disturbanceId);
        return event != null && "RECOVERED".equals(event.status());
    }

    private boolean isEffectConfirmed(DispatchCommand command, TrainState train, TrainRunProfile profile) {
        if (train == null) {
            return false;
        }
        return switch (command.commandType()) {
            case "SHORTEN_DWELL" -> false;
            case "SPEED_BIAS" -> hasDepartedOrMoving(train);
            case "EXTEND_DWELL" -> isDwelling(train) && train.dwellElapsedSeconds() >= adjustedDwellTarget(
                payloadInt(command, "deltaDwellSec", 0)
            );
            case "HEADWAY_ADJUST" -> isHeadwayRecovered(command, profile);
            case "HOLD", "HOLD_TRAIN" -> isStopped(train) && !isTimedHoldStillRequired(command, train);
            case "SPEED_LIMIT", "TEMP_SPEED_LIMIT" -> !requiresManualRelease(command);
            case "SPEED_FACTOR", "LIMIT_FACTOR" -> true;
            case "DEPART" -> true;
            default -> false;
        };
    }

    private boolean isAppliedObserved(
        DispatchCommand command,
        TrainState train,
        MovementAuthority authority,
        TrainRunProfile profile
    ) {
        if (train == null) {
            return false;
        }
        return switch (command.commandType()) {
            case "HOLD", "HOLD_TRAIN" -> isStopped(train);
            case "EXTEND_DWELL" -> isDwelling(train) && isStopped(train);
            case "SHORTEN_DWELL" -> shouldReleaseDwell(command, train) || hasDepartedOrMoving(train);
            case "HEADWAY_ADJUST" -> isHeadwayRecovered(command, profile)
                || shouldReleaseForShorterHeadway(command, train)
                || shouldSlowForLongerHeadway(command, train);
            case "SPEED_BIAS" -> hasDepartedOrMoving(train) || hasUsableAuthority(authority);
            case "SPEED_LIMIT", "TEMP_SPEED_LIMIT" -> authority != null
                && authority.speedLimitMetersPerSecond() <= payloadDouble(command, "detail", Double.MAX_VALUE);
            case "SPEED_FACTOR", "LIMIT_FACTOR" -> true;
            default -> false;
        };
    }

    private boolean isHeadwayRecovered(DispatchCommand command, TrainRunProfile profile) {
        if (profile == null || profile.headwayActualSec() == null) {
            return false;
        }
        int targetHeadway = payloadInt(command, "targetHeadwaySec", currentPlan.departureIntervalSec());
        int tolerance = Math.max(5, currentPlan.departureIntervalSec() / 10);
        return Math.abs(profile.headwayActualSec() - targetHeadway) <= tolerance;
    }

    private boolean shouldReleaseForShorterHeadway(DispatchCommand command, TrainState train) {
        int targetHeadway = payloadInt(command, "targetHeadwaySec", currentPlan.departureIntervalSec());
        return targetHeadway < currentPlan.departureIntervalSec()
            && shouldReleaseDwell(command, train);
    }

    private boolean shouldSlowForLongerHeadway(DispatchCommand command, TrainState train) {
        int targetHeadway = payloadInt(command, "targetHeadwaySec", currentPlan.departureIntervalSec());
        return targetHeadway > currentPlan.departureIntervalSec()
            && (isDwelling(train) || train.speedMetersPerSecond() <= properties.getBaseCruiseSpeedMps() * 0.9);
    }

    private boolean shouldReleaseDwell(DispatchCommand command, TrainState train) {
        int delta = payloadInt(command, "deltaDwellSec", 0);
        return isDwelling(train) && train.dwellElapsedSeconds() >= adjustedDwellTarget(delta);
    }

    private boolean hasDepartedOrMoving(TrainState train) {
        return !isDwelling(train) || train.speedMetersPerSecond() > properties.getStopSpeedThresholdMps();
    }

    private boolean isStopped(TrainState train) {
        return train.zeroSpeed() || train.speedMetersPerSecond() <= properties.getStopSpeedThresholdMps();
    }

    private boolean hasUsableAuthority(MovementAuthority authority) {
        return authority != null && authority.speedLimitMetersPerSecond() > properties.getStopSpeedThresholdMps();
    }

    private boolean isTimedHoldStillRequired(DispatchCommand command, TrainState train) {
        int holdUntilDwellSec = payloadInt(command, "holdUntilDwellSec", -1);
        return holdUntilDwellSec >= 0 && train.dwellElapsedSeconds() < holdUntilDwellSec;
    }

    private boolean isTimedOut(DispatchCommand command, Instant simulatedAt) {
        if (CommandStatus.APPLIED.equals(command.status())) {
            return false;
        }
        long elapsedSec = simulatedAt.getEpochSecond() - command.createdAt().getEpochSecond();
        return elapsedSec >= properties.getCommandEffectTimeoutSec();
    }

    private boolean requiresManualRelease(DispatchCommand command) {
        return "MANUAL".equals(command.reason())
            && ("SPEED_LIMIT".equals(command.commandType()) || "TEMP_SPEED_LIMIT".equals(command.commandType()));
    }

    private DispatchCommand commandWithStatus(DispatchCommand command, String status, Instant appliedAt) {
        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            command.payload(),
            command.reason(),
            status,
            command.createdAt(),
            appliedAt
        );
    }

    private DispatchCommand commandWithFeedback(
        DispatchCommand command,
        String status,
        DispatchCommandFeedback feedback
    ) {
        Map<String, Object> payload = command.payload() == null
            ? new HashMap<>()
            : new HashMap<>(command.payload());
        payload.put("lastFeedbackSource", feedback.feedbackSource());
        payload.put("lastFeedbackStatus", feedback.feedbackStatus());
        payload.put("lastFeedbackReason", feedback.reason());
        payload.put(
            "lastFeedbackAt",
            feedback.feedbackAt() == null ? Instant.now().toString() : feedback.feedbackAt().toString()
        );
        payload.put("lastFeedbackDetails", feedback.details());
        Instant appliedAt = CommandStatus.APPLIED.equals(status)
            || CommandStatus.EFFECT_CONFIRMED.equals(status)
            || CommandStatus.COMPLETED.equals(status)
            ? (feedback.feedbackAt() == null ? Instant.now() : feedback.feedbackAt())
            : command.appliedAt();
        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            payload,
            command.reason(),
            status,
            command.createdAt(),
            appliedAt
        );
    }

    private String nextStatusFromFeedback(String currentStatus, String feedbackStatus) {
        if (!isEffectTrackedStatus(currentStatus)) {
            return currentStatus;
        }
        if (CommandStatus.APPLIED.equals(feedbackStatus)) {
            return CommandStatus.PENDING.equals(currentStatus) || CommandStatus.SENT.equals(currentStatus)
                ? CommandStatus.APPLIED
                : currentStatus;
        }
        if (CommandStatus.EFFECT_CONFIRMED.equals(feedbackStatus) || CommandStatus.COMPLETED.equals(feedbackStatus)) {
            return CommandStatus.EFFECT_CONFIRMED;
        }
        if (CommandStatus.SKIPPED.equals(feedbackStatus) || CommandStatus.CANCELLED.equals(feedbackStatus)) {
            return feedbackStatus;
        }
        return currentStatus;
    }

    private boolean shouldRecordFeedback(String currentStatus, String feedbackStatus) {
        return isEffectTrackedStatus(currentStatus)
            && (CommandStatus.APPLIED.equals(feedbackStatus)
            || CommandStatus.EFFECT_CONFIRMED.equals(feedbackStatus)
            || CommandStatus.COMPLETED.equals(feedbackStatus)
            || CommandStatus.SKIPPED.equals(feedbackStatus)
            || CommandStatus.CANCELLED.equals(feedbackStatus));
    }

    public synchronized List<DispatchConstraint> constraintsForTrains(List<TrainState> trains) {
        return constraintsForTrains(trains, true);
    }

    public synchronized List<DispatchConstraint> previewConstraintsForTrains(List<TrainState> trains) {
        return constraintsForTrains(trains, false);
    }

    private List<DispatchConstraint> constraintsForTrains(List<TrainState> trains, boolean consumeQueuedCommands) {
        Map<String, List<DispatchCommand>> commandsByTrain = new HashMap<>();
        Map<String, Set<String>> seenCommandIdsByTrain = new HashMap<>();
        for (DispatchCommand command : manualCommands) {
            if (command.trainId() == null || command.trainId().isBlank()) {
                continue;
            }
            if (!isActiveCommandStatus(command.status())) {
                continue;
            }
            addCommandForTrain(commandsByTrain, seenCommandIdsByTrain, command);
        }
        for (DispatchCommand command : activeCommands) {
            if (command.trainId() == null || command.trainId().isBlank()) {
                continue;
            }
            if (!isActiveCommandStatus(command.status())) {
                continue;
            }
            addCommandForTrain(commandsByTrain, seenCommandIdsByTrain, command);
        }

        Map<String, AutomaticRegulation> automaticRegulations = automaticHeadwayRegulations(trains);
        List<DispatchConstraint> constraints = trains.stream()
            .map(train -> constraintForTrain(
                train,
                commandsByTrain.getOrDefault(train.id(), List.of()),
                automaticRegulations.getOrDefault(train.id(), AutomaticRegulation.none())
            ))
            .toList();

        if (consumeQueuedCommands) {
            List<DispatchCommand> consumed = commandQueue.peekPending().stream()
                .filter(command -> !"REROUTE".equals(command.commandType()))
                .filter(command -> !"REQUEST_ROUTE".equals(command.commandType()))
                .toList();
            if (!consumed.isEmpty()) {
                drainQueuedCommands(consumed);
            }
        }
        return constraints;
    }

    private void addCommandForTrain(
        Map<String, List<DispatchCommand>> commandsByTrain,
        Map<String, Set<String>> seenCommandIdsByTrain,
        DispatchCommand command
    ) {
        Set<String> seenIds = seenCommandIdsByTrain.computeIfAbsent(command.trainId(), ignored -> new HashSet<>());
        if (!seenIds.add(command.id())) {
            return;
        }
        commandsByTrain.computeIfAbsent(command.trainId(), ignored -> new ArrayList<>()).add(command);
    }

    public synchronized DispatchSnapshot snapshot() {
        return latestSnapshot;
    }

    public synchronized CurrentRunPlan currentPlan() {
        return currentPlan;
    }

    /** 返回时刻表中定义的站点列表（按里程排序） */
    public synchronized List<StationInfo> stations() {
        return planLoader.stations();
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

    private void syncDisturbanceRecords() {
        for (DisturbanceEvent event : disturbanceDetector.events()) {
            if (simulationRunId.equals(event.simulationRunId())) {
                disturbanceRecordStore.update(event);
            }
        }
    }

    private DispatchConstraint constraintForTrain(
        TrainState train,
        List<DispatchCommand> commands,
        AutomaticRegulation automaticRegulation
    ) {
        boolean holdTrain = automaticRegulation.holdTrain();
        double speedFactor = automaticRegulation.speedFactor();
        Double targetSpeed = automaticRegulation.targetSpeedMetersPerSecond();
        boolean releaseStationStop = shouldReleaseScheduledDwell(train);
        List<String> reasons = new ArrayList<>(automaticRegulation.reasons());
        List<String> sourceCommandIds = new ArrayList<>();
        Integer dwellReleaseTarget = null;
        if (releaseStationStop) {
            reasons.add("SCHEDULED_DWELL_COMPLETE(targetDwell=" + scheduledDwellTarget() + "s)");
        }

        for (DispatchCommand command : commands) {
            sourceCommandIds.add(command.id());
            switch (command.commandType()) {
                case "HOLD", "HOLD_TRAIN" -> {
                    holdTrain = true;
                    reasons.add(reason(command));
                }
                case "EXTEND_DWELL", "SHORTEN_DWELL" -> {
                    int delta = payloadInt(command, "deltaDwellSec", 0);
                    int targetDwell = delta < 0 ? properties.getMinDwellSec() : adjustedDwellTarget(delta);
                    dwellReleaseTarget = dwellReleaseTarget == null
                        ? targetDwell
                        : Math.max(dwellReleaseTarget, targetDwell);
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
                        dwellReleaseTarget = dwellReleaseTarget == null
                            ? properties.getMinDwellSec()
                            : Math.min(dwellReleaseTarget, properties.getMinDwellSec());
                        if (isDwelling(train) && train.dwellElapsedSeconds() >= properties.getMinDwellSec()) {
                            releaseStationStop = true;
                        }
                    } else if (targetHeadway > currentPlan.departureIntervalSec()) {
                        dwellReleaseTarget = dwellReleaseTarget == null
                            ? properties.getMaxDwellSec()
                            : Math.max(dwellReleaseTarget, properties.getMaxDwellSec());
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
                        dwellReleaseTarget = dwellReleaseTarget == null
                            ? properties.getMinDwellSec()
                            : Math.min(dwellReleaseTarget, properties.getMinDwellSec());
                        releaseStationStop = true;
                    } else {
                        speedFactor = Math.min(speedFactor, ratio);
                    }
                    reasons.add(reason(command));
                }
                default -> reasons.add(reason(command));
            }
        }
        if (isDwelling(train)) {
            int targetDwell = dwellReleaseTarget == null ? currentPlan.defaultDwellTimeSec() : dwellReleaseTarget;
            if (train.dwellElapsedSeconds() >= targetDwell) {
                releaseStationStop = true;
                reasons.add("SCHEDULE_DWELL_COMPLETE(targetDwell=" + targetDwell + "s)");
            } else {
                holdTrain = true;
                reasons.add("SCHEDULE_DWELL(targetDwell=" + targetDwell + "s)");
            }
        }
        if (commands.isEmpty() && reasons.isEmpty()) {
            return DispatchConstraint.none(train.id());
        }
        return new DispatchConstraint(
            train.id(),
            holdTrain,
            Math.max(0, Math.min(1, speedFactor)),
            targetSpeed,
            releaseStationStop && !holdTrain,
            reasons.isEmpty() ? "NORMAL" : String.join("; ", reasons),
            sourceCommandIds
        );
    }

    private Map<String, AutomaticRegulation> automaticHeadwayRegulations(List<TrainState> trains) {
        if (trains.size() < 2) {
            return Map.of();
        }
        List<TrainState> ordered = trains.stream()
            .sorted(Comparator.comparingDouble(TrainState::positionMeters))
            .toList();
        Map<String, AutomaticRegulation> regulations = new HashMap<>();
        double stationHoldGap = Math.max(300, properties.getBaseCruiseSpeedMps() * 35);
        double approachControlGap = Math.max(stationHoldGap, properties.getBaseCruiseSpeedMps() * 55);
        for (int i = 0; i < ordered.size() - 1; i++) {
            TrainState train = ordered.get(i);
            TrainState front = ordered.get(i + 1);
            double gapToFrontTail = front.positionMeters() - front.lengthMeters() - train.positionMeters();
            if (gapToFrontTail <= 0 || gapToFrontTail >= approachControlGap) {
                continue;
            }

            List<String> reasons = new ArrayList<>();
            boolean frontDwelling = isDwelling(front) || front.speedMetersPerSecond() <= properties.getStopSpeedThresholdMps();
            boolean trainDwelling = isDwelling(train);
            if (trainDwelling && gapToFrontTail < stationHoldGap) {
                reasons.add("STATION_HOLD_FOR_HEADWAY(front=" + front.id() + ",gap=" + Math.round(gapToFrontTail) + "m)");
                regulations.put(train.id(), new AutomaticRegulation(true, 1.0, null, reasons));
                continue;
            }
            if (!trainDwelling && frontDwelling) {
                double ratio = Math.max(0.18, Math.min(1.0, gapToFrontTail / approachControlGap));
                double targetSpeed = Math.max(1.5, properties.getBaseCruiseSpeedMps() * ratio * 0.65);
                reasons.add("APPROACH_CONTROL_FOR_HEADWAY(front=" + front.id() + ",gap=" + Math.round(gapToFrontTail) + "m)");
                regulations.put(train.id(), new AutomaticRegulation(false, ratio, targetSpeed, reasons));
            }
        }
        return regulations;
    }

    private boolean isDwelling(TrainState train) {
        return "DWELLING".equals(train.status())
            || (train.currentStationId() != null && !train.currentStationId().isBlank())
            || train.dwellElapsedSeconds() > 0;
    }

    private int adjustedDwellTarget(int deltaSeconds) {
        int target = scheduledDwellTarget() + deltaSeconds;
        return Math.max(properties.getMinDwellSec(), Math.min(properties.getMaxDwellSec(), target));
    }

    private boolean shouldReleaseScheduledDwell(TrainState train) {
        return isDwelling(train) && train.dwellElapsedSeconds() >= scheduledDwellTarget();
    }

    private int scheduledDwellTarget() {
        return Math.max(
            properties.getMinDwellSec(),
            Math.min(properties.getMaxDwellSec(), currentPlan.defaultDwellTimeSec())
        );
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
            disturbanceDetector.events(),
            visibleCommandHistory()
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
                profile.frontTrainId(),
                profile.headwayActualSec(),
                profile.headwayDeviationSec(),
                profile.headwayState(),
                profile.headwayAction(),
                profile.dwellDeviationSec()
            ))
            .toList();
        List<DispatchSnapshot.DisturbanceView> disturbanceViews = disturbances.stream()
            .filter(event -> "OPEN".equals(event.status())
                || "HANDLED".equals(event.status())
                || "RECOVERED".equals(event.status()))
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
            .anyMatch(command -> isActiveCommandStatus(command.status()));
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
            .filter(command -> isActiveCommandStatus(command.status()))
            .toList();
    }

    private void replaceManualCommand(DispatchCommand command) {
        for (int i = 0; i < manualCommands.size(); i++) {
            if (manualCommands.get(i).id().equals(command.id())) {
                manualCommands.set(i, command);
                return;
            }
        }
    }

    private boolean isActiveCommandStatus(String status) {
        return CommandStatus.PENDING.equals(status)
            || CommandStatus.SENT.equals(status)
            || CommandStatus.APPLIED.equals(status);
    }

    private List<DispatchCommand> visibleCommandHistory() {
        List<DispatchCommand> commands = new ArrayList<>(commandRecordStore.list(simulationRunId));
        if (commands.size() <= 12) {
            return commands;
        }
        return commands.subList(commands.size() - 12, commands.size());
    }

    private record AutomaticRegulation(
        boolean holdTrain,
        double speedFactor,
        Double targetSpeedMetersPerSecond,
        List<String> reasons
    ) {
        private AutomaticRegulation {
            reasons = List.copyOf(reasons);
        }

        static AutomaticRegulation none() {
            return new AutomaticRegulation(false, 1.0, null, List.of());
        }
    }
}
