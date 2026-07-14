package com.railwaysim.dispatch;

import com.railwaysim.dispatch.command.CommandQueue;
import com.railwaysim.dispatch.command.CommandRecordStore;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.command.CommandValidator;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.disturbance.DisturbanceDetector;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.disturbance.DisturbanceRecordStore;
import com.railwaysim.dispatch.disturbance.DisturbanceType;
import com.railwaysim.dispatch.monitor.InMemoryStationRecordStore;
import com.railwaysim.dispatch.monitor.StationInfo;
import com.railwaysim.dispatch.monitor.StationHeadwayObservation;
import com.railwaysim.dispatch.monitor.TrainRunMonitor;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.operation.OperationPlan;
import com.railwaysim.dispatch.operation.OperationPlanRequest;
import com.railwaysim.dispatch.operation.OperationPlanningService;
import com.railwaysim.dispatch.operation.OperationRouteCandidate;
import com.railwaysim.dispatch.operation.OperationRouteTemplate;
import com.railwaysim.dispatch.optimization.LineHeadwayOptimizationResult;
import com.railwaysim.dispatch.optimization.LineHeadwayOptimizer;
import com.railwaysim.dispatch.optimization.LineRegulationContext;
import com.railwaysim.dispatch.optimization.LineRegulationPlan;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.PlannedStop;
import com.railwaysim.dispatch.plan.TrainServicePlan;
import com.railwaysim.dispatch.route.RouteDispatchDecision;
import com.railwaysim.dispatch.route.RouteDispatchRecordStore;
import com.railwaysim.dispatch.route.DispatchRouteCandidate;
import com.railwaysim.dispatch.route.RouteCatalog;
import com.railwaysim.dispatch.route.RouteIntentResolver;
import com.railwaysim.dispatch.route.RouteIntentArbiter;
import com.railwaysim.dispatch.route.RouteIntentSelection;
import com.railwaysim.dispatch.route.RouteReservation;
import com.railwaysim.dispatch.route.RouteReservationState;
import com.railwaysim.dispatch.route.TrainRouteIntent;
import com.railwaysim.dispatch.strategy.TrainRegulationAction;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.RouteState;
import com.railwaysim.signal.dispatch.SignalDispatchPlanPublication;
import com.railwaysim.signal.dispatch.SignalDispatchPlanRegistry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final OperationPlanLoader planLoader;
    private final DispatchProperties properties;
    private final TrainRunMonitor trainRunMonitor;
    private final DisturbanceDetector disturbanceDetector;
    private final CommandValidator commandValidator;
    private final CommandQueue commandQueue;
    private final DisturbanceRecordStore disturbanceRecordStore;
    private final CommandRecordStore commandRecordStore;
    private final InMemoryStationRecordStore stationRecordStore;
    private final RouteDispatchRecordStore routeDispatchRecordStore;
    private final RouteCatalog routeCatalog;
    private final RouteIntentResolver routeIntentResolver;
    private final RouteIntentArbiter routeIntentArbiter;
    private final OperationPlanningService operationPlanningService;
    private final SignalDispatchPlanRegistry signalDispatchPlanRegistry;
    private final LineHeadwayOptimizer lineHeadwayOptimizer;
    private final List<DispatchCommand> manualCommands = new CopyOnWriteArrayList<>();

    private String simulationRunId = UUID.randomUUID().toString();
    private Instant simulationStart = Instant.now();
    private Instant lastEvaluatedAt = Instant.EPOCH;
    private CurrentRunPlan currentPlan;
    private List<TrainRunProfile> latestProfiles = List.of();
    private List<TrainState> latestTrains = List.of();
    private List<DispatchCommand> activeCommands = List.of();
    private DispatchSnapshot latestSnapshot = DispatchSnapshot.empty();
    private LineRegulationPlan latestLineRegulationPlan;
    private final Map<String, String> lastConstraintLogByTrain = new HashMap<>();
    private final Set<String> issuedServiceIds = new HashSet<>();
    private final Map<String, String> departureCommandIdByService = new HashMap<>();
    private final Map<String, Instant> routeWaitingSinceByKey = new HashMap<>();

    public DispatchService(
        OperationPlanLoader planLoader,
        DispatchProperties properties,
        TrainRunMonitor trainRunMonitor,
        DisturbanceDetector disturbanceDetector,
        CommandValidator commandValidator,
        CommandQueue commandQueue,
        DisturbanceRecordStore disturbanceRecordStore,
        CommandRecordStore commandRecordStore,
        InMemoryStationRecordStore stationRecordStore,
        RouteDispatchRecordStore routeDispatchRecordStore,
        RouteCatalog routeCatalog,
        RouteIntentResolver routeIntentResolver,
        RouteIntentArbiter routeIntentArbiter,
        OperationPlanningService operationPlanningService,
        SignalDispatchPlanRegistry signalDispatchPlanRegistry,
        LineHeadwayOptimizer lineHeadwayOptimizer
    ) {
        this.planLoader = planLoader;
        this.properties = properties;
        this.trainRunMonitor = trainRunMonitor;
        this.disturbanceDetector = disturbanceDetector;
        this.commandValidator = commandValidator;
        this.commandQueue = commandQueue;
        this.disturbanceRecordStore = disturbanceRecordStore;
        this.commandRecordStore = commandRecordStore;
        this.stationRecordStore = stationRecordStore;
        this.routeDispatchRecordStore = routeDispatchRecordStore;
        this.routeCatalog = routeCatalog;
        this.routeIntentResolver = routeIntentResolver;
        this.routeIntentArbiter = routeIntentArbiter;
        this.operationPlanningService = operationPlanningService;
        this.signalDispatchPlanRegistry = signalDispatchPlanRegistry;
        this.lineHeadwayOptimizer = lineHeadwayOptimizer;
        this.currentPlan = planLoader.resolve(Instant.now());
        this.latestLineRegulationPlan = LineRegulationPlan.empty(
            "LRP-INIT", simulationStart, this.currentPlan.departureIntervalSec());
        this.latestSnapshot = buildSnapshot(currentPlan, latestProfiles, List.of(), List.of());
    }

    public synchronized void reset() {
        simulationRunId = UUID.randomUUID().toString();
        simulationStart = Instant.now();
        lastEvaluatedAt = Instant.EPOCH;
        currentPlan = planLoader.resolve(simulationStart);
        latestProfiles = List.of();
        latestTrains = List.of();
        activeCommands = List.of();
        lastConstraintLogByTrain.clear();
        issuedServiceIds.clear();
        departureCommandIdByService.clear();
        routeWaitingSinceByKey.clear();
        latestLineRegulationPlan = LineRegulationPlan.empty(
            "LRP-RESET", simulationStart, currentPlan.departureIntervalSec());
        operationPlanningService.clear();
        manualCommands.clear();
        commandQueue.clear();
        routeDispatchRecordStore.clear();
        signalDispatchPlanRegistry.clear();
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

    public synchronized DispatchCommand submit(DispatchCommand command) {
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
        if (RouteDispatchRecordStore.isRouteCommand(stored)) {
            stored = commandValidator.validate(List.of(stored), List.of()).get(0);
            stored = routeDispatchRecordStore.trackSubmittedRouteCommand(simulationRunId, stored);
        }
        manualCommands.add(stored);
        commandRecordStore.save(stored);
        log.info("[DispatchLoop] manual command accepted {}", commandSummary(stored));
        if (RouteDispatchRecordStore.isRouteCommand(stored) && CommandStatus.PENDING.equals(stored.status())) {
            commandQueue.enqueue(List.of(stored));
            log.info("[DispatchLoop] route command queued {}", commandSummary(stored));
        }
        refreshSnapshot();
        return stored;
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
                routeDispatchRecordStore.markCommandSent(command);
                updated.add(command);
                log.info("[DispatchLoop] command skipped before send {}", commandSummary(command));
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
            routeDispatchRecordStore.markCommandSent(sent);
            replaceManualCommand(sent);
            updated.add(sent);
            log.info("[DispatchLoop] command sent {}", commandSummary(sent));
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
            routeDispatchRecordStore.updateFromFeedback(progressed, feedback);
            updateOperationPlanFromFeedback(progressed, feedback);
            if (RouteDispatchRecordStore.isRouteCancellation(progressed.commandType())) {
                String reservationId = payloadString(progressed, "reservationId");
                if (reservationId != null) {
                    routeDispatchRecordStore.updateCancellationFeedback(reservationId, progressed, feedback);
                }
            }
            replaceManualCommand(progressed);
            commandById.put(progressed.id(), progressed);
            updated.add(progressed);
            log.info(
                "[DispatchLoop] feedback accepted command={} source={} feedback={} nextStatus={} reason={}",
                progressed.id(),
                feedback.feedbackSource(),
                feedback.feedbackStatus(),
                progressed.status(),
                feedback.reason()
            );
        }
        if (!updated.isEmpty()) {
            activeCommands = mergeActiveCommands(updated);
            refreshSnapshot();
        }
    }

    public synchronized void syncRouteReservations(List<RouteState> routeStates, Instant simulatedAt) {
        if (routeStates == null || routeStates.isEmpty()) {
            return;
        }
        Map<String, RouteState> routeStateById = new HashMap<>();
        for (RouteState routeState : routeStates) {
            routeStateById.put(routeState.routeId(), routeState);
        }
        boolean changed = false;
        for (RouteReservation reservation : routeDispatchRecordStore.listReservations(simulationRunId)) {
            if (!RouteReservationState.ACCEPTED.equals(reservation.state())) {
                continue;
            }
            RouteState routeState = routeStateById.get(reservation.routeId());
            boolean stillEstablished = routeState != null
                && routeState.status().holdsInterlockingResources()
                && reservation.trainId().equals(routeState.establishedByTrainId());
            if (!stillEstablished) {
                changed |= routeDispatchRecordStore.releaseReservation(reservation.reservationId(), simulatedAt);
            }
        }
        if (changed) {
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
            RouteReservation reservation = routeDispatchRecordStore.reservationForCommand(commandId);
            if (reservation != null && RouteReservationState.ACCEPTED.equals(reservation.state())) {
                DispatchCommand cancellation = createRouteCancellationCommand(
                    reservation, Instant.now(), "MANUAL_ROUTE_CANCEL");
                commandRecordStore.save(cancellation);
                commandQueue.enqueue(List.of(cancellation));
                activeCommands = mergeActiveCommands(List.of(cancellation));
                log.info("[DispatchLoop] route cancellation queued {}", commandSummary(cancellation));
                refreshSnapshot();
                return;
            }
            DispatchCommand cancelled = commandWithStatus(command, CommandStatus.CANCELLED, Instant.now());
            commandRecordStore.update(cancelled);
            routeDispatchRecordStore.cancelCommand(cancelled, cancelled.appliedAt() == null ? Instant.now() : cancelled.appliedAt());
            replaceManualCommand(cancelled);
            activeCommands = mergeActiveCommands(List.of(cancelled));
            log.info("[DispatchLoop] command cancelled {}", commandSummary(cancelled));
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
        latestTrains = trains == null ? List.of() : List.copyOf(trains);
        latestProfiles = trainRunMonitor.update(simulationRunId, context.simulatedTime(), currentPlan, trains);

        List<DisturbanceEvent> created = disturbanceDetector.detect(
            simulationRunId,
            context.simulatedTime(),
            currentPlan,
            latestProfiles
        );
        for (DisturbanceEvent event : created) {
            disturbanceRecordStore.save(event);
            logDisturbanceCreated(event);
        }
        syncDisturbanceRecords();
        progressActiveCommands(context.simulatedTime(), trains, authorities, latestProfiles);
        expirePendingRouteCommands(context.simulatedTime());
        progressRequestedRouteTimeouts(context.simulatedTime());

        List<DisturbanceEvent> openEvents = disturbanceDetector.openEvents().stream()
            .filter(event -> "OPEN".equals(event.status()))
            .toList();
        LineHeadwayOptimizationResult lineOptimization = lineHeadwayOptimizer.optimize(new LineRegulationContext(
            simulationRunId,
            context.simulatedTime(),
            currentPlan,
            latestProfiles,
            trains,
            authorities,
            openEvents,
            activeCommands,
            routeDispatchRecordStore.listReservations(simulationRunId)
        ));
        latestLineRegulationPlan = lineOptimization.plan();
        List<DispatchCommand> generated = suppressDuplicateDisturbanceCommands(lineOptimization.commands());
        List<DispatchCommand> routeCommands = automaticRouteCommands(context.simulatedTime(), trains, authorities);
        List<DispatchCommand> routeCancellationCommands = automaticRouteCancellationCommands(context.simulatedTime());
        List<DispatchCommand> operationPlanRouteCommands = automaticOperationPlanRouteCommands(context.simulatedTime());
        List<DispatchCommand> departureCommands = automaticDepartureCommands(context.simulatedTime(), trains);
        List<DispatchCommand> combined = new ArrayList<>(generated);
        combined.addAll(routeCommands);
        combined.addAll(routeCancellationCommands);
        combined.addAll(operationPlanRouteCommands);
        combined.addAll(departureCommands);
        List<DispatchCommand> validated = commandValidator.validate(combined, authorities, context.simulatedTime());
        List<DispatchCommand> tracked = trackGeneratedRouteCommands(validated);
        logCommandBatch("generated", generated);
        logCommandBatch("route-generated", routeCommands);
        logCommandBatch("route-cancel-generated", routeCancellationCommands);
        logCommandBatch("operation-plan-route-generated", operationPlanRouteCommands);
        logCommandBatch("departure-generated", departureCommands);
        logCommandBatch("validated", tracked);
        commandQueue.enqueue(tracked.stream()
            .filter(command -> CommandStatus.PENDING.equals(command.status()))
            .toList());
        for (DispatchCommand command : tracked) {
            commandRecordStore.save(command);
        }
        activeCommands = mergeActiveCommands(tracked);
        refreshSnapshot();
    }

    private List<DispatchCommand> automaticRouteCommands(
        Instant simulatedAt,
        List<TrainState> trains,
        List<MovementAuthority> authorities
    ) {
        List<TrainRouteIntent> intents = applyRouteWaitingPriority(
            routeIntentResolver.resolve(simulatedAt, trains, authorities), simulatedAt);
        if (intents.isEmpty()) {
            return List.of();
        }
        Instant cooldownCutoff = simulatedAt.minusSeconds(Math.max(0, properties.getRouteRequestCooldownSeconds()));
        List<RouteReservation> activeReservations = routeDispatchRecordStore.listReservations(simulationRunId).stream()
            .filter(reservation -> RouteReservationState.REQUESTED.equals(reservation.state())
                || RouteReservationState.ACCEPTED.equals(reservation.state()))
            .toList();
        List<TrainRouteIntent> eligible = intents.stream()
            .filter(intent -> routeDispatchRecordStore.canRetry(
                simulationRunId, intent.trainId(), intent.routeId(), properties.getRouteMaxRetries()))
            .filter(intent -> !routeDispatchRecordStore.hasRecentRouteRequest(
                simulationRunId,
                intent.trainId(),
                intent.routeId(),
                cooldownCutoff
            ))
            .filter(intent -> activeReservations.stream().noneMatch(reservation ->
                !intent.trainId().equals(reservation.trainId())
                    && routeIntentArbiter.conflicts(intent.routeId(), reservation.routeId())))
            .toList();
        List<RouteIntentSelection> selections = routeIntentArbiter.arbitrate(eligible);
        for (RouteIntentSelection selection : selections) {
            routeWaitingSinceByKey.remove(routeWaitingKey(selection.selectedIntent()));
        }
        return selections.stream().map(this::commandFromRouteSelection).toList();
    }

    private List<DispatchCommand> automaticOperationPlanRouteCommands(Instant simulatedAt) {
        List<OperationPlan> duePlans = operationPlanningService.duePlans(simulationRunId, simulatedAt);
        if (duePlans.isEmpty()) {
            return List.of();
        }
        List<DispatchCommand> commands = new ArrayList<>();
        for (OperationPlan plan : duePlans) {
            if (hasActiveRouteRequestForPlan(plan.planId())) {
                continue;
            }
            String commandId = "DC-op-route-" + plan.planId() + "-" + simulatedAt.toEpochMilli();
            Map<String, Object> payload = new HashMap<>();
            payload.put("simulationRunId", simulationRunId);
            payload.put("source", "OPERATION_PLAN");
            payload.put("operationPlanId", plan.planId());
            payload.put("operationPlanVersion", plan.version());
            payload.put("routeId", plan.routeId());
            payload.put("detail", plan.routeId());
            payload.put("direction", plan.direction());
            payload.put("originPointId", plan.originPointId());
            payload.put("destinationPointId", plan.destinationPointId());
            payload.put("viaPointIds", plan.viaPointIds());
            payload.put("segmentIds", plan.segmentIds());
            payload.put("plannedDepartureAt", plan.plannedDepartureAt().toString());
            payload.put("dispatchDelaySec", Math.max(0,
                simulatedAt.getEpochSecond() - plan.plannedDepartureAt().getEpochSecond()));
            commands.add(new DispatchCommand(
                commandId,
                plan.trainId(),
                "REQUEST_ROUTE",
                payload,
                "OPERATION_PLAN",
                CommandStatus.PENDING,
                simulatedAt,
                null
            ));
            operationPlanningService.markRouteRequested(plan.planId(), commandId, simulatedAt);
        }
        return List.copyOf(commands);
    }

    private boolean hasActiveRouteRequestForPlan(String operationPlanId) {
        if (operationPlanId == null || operationPlanId.isBlank()) {
            return false;
        }
        for (DispatchCommand command : commandRecordStore.list(simulationRunId)) {
            if (operationPlanId.equals(payloadString(command, "operationPlanId"))
                && RouteDispatchRecordStore.isRouteCommand(command)
                && isEffectTrackedStatus(command.status())) {
                return true;
            }
        }
        return false;
    }

    private List<TrainRouteIntent> applyRouteWaitingPriority(List<TrainRouteIntent> intents, Instant simulatedAt) {
        if (intents.isEmpty()) {
            return intents;
        }
        Set<String> visibleKeys = new HashSet<>();
        List<TrainRouteIntent> adjusted = new ArrayList<>();
        for (TrainRouteIntent intent : intents) {
            String key = routeWaitingKey(intent);
            visibleKeys.add(key);
            Instant waitingSince = routeWaitingSinceByKey.computeIfAbsent(key, ignored -> simulatedAt);
            double waitingSeconds = Math.max(0, simulatedAt.getEpochSecond() - waitingSince.getEpochSecond());
            double waitingBonus = Math.min(
                properties.getRouteWaitingPriorityMaxBonus(),
                waitingSeconds * properties.getRouteWaitingPriorityPerSecond()
            );
            adjusted.add(new TrainRouteIntent(
                intent.trainId(), intent.routeId(), intent.reason(), intent.distanceToEntryMeters(),
                intent.etaToEntrySeconds(), intent.priorityScore() + waitingBonus, waitingSeconds,
                intent.createdAt(), intent.validUntil()
            ));
        }
        routeWaitingSinceByKey.keySet().removeIf(key -> !visibleKeys.contains(key));
        return List.copyOf(adjusted);
    }

    private String routeWaitingKey(TrainRouteIntent intent) {
        return intent.trainId() + "|" + intent.routeId();
    }

    private List<DispatchCommand> automaticDepartureCommands(Instant simulatedAt, List<TrainState> trains) {
        if (planLoader.services().isEmpty()) {
            return List.of();
        }
        Set<String> activeTrainIds = trains == null
            ? Set.of()
            : trains.stream().map(TrainState::id).collect(java.util.stream.Collectors.toSet());
        List<DispatchCommand> commands = new ArrayList<>();
        for (TrainServicePlan service : planLoader.services()) {
            PlannedStop origin = service.origin();
            if (origin == null || issuedServiceIds.contains(service.serviceId())
                || activeTrainIds.contains(service.trainId())) {
                continue;
            }
            Instant plannedDeparture = plannedDepartureAt(service);
            if (simulatedAt.isBefore(plannedDeparture)) {
                continue;
            }
            PlannedStop terminus = service.terminus();
            Map<String, Object> payload = new HashMap<>();
            payload.put("simulationRunId", simulationRunId);
            payload.put("source", "FORMAL_SERVICE_PLAN");
            payload.put("serviceId", service.serviceId());
            payload.put("circulationId", service.circulationId());
            payload.put("trainNo", service.trainNo());
            payload.put("linkId", service.linkId());
            payload.put("offsetMeters", service.offsetMeters());
            payload.put("fromStation", origin.stationId());
            payload.put("toStation", terminus == null ? origin.stationId() : terminus.stationId());
            payload.put("direction", service.direction());
            payload.put("plannedDepartureAt", plannedDeparture.toString());
            payload.put("dispatchDelaySec", Math.max(0,
                simulatedAt.getEpochSecond() - plannedDeparture.getEpochSecond()));
            String commandId = "DC-depart-" + service.serviceId();
            commands.add(new DispatchCommand(
                commandId,
                service.trainId(),
                "DEPART",
                payload,
                "FORMAL_SERVICE_PLAN",
                CommandStatus.PENDING,
                simulatedAt,
                null
            ));
            issuedServiceIds.add(service.serviceId());
            departureCommandIdByService.put(service.serviceId(), commandId);
        }
        return List.copyOf(commands);
    }

    private DispatchCommand commandFromRouteSelection(RouteIntentSelection selection) {
        DispatchCommand command = commandFromRouteIntent(selection.selectedIntent());
        Map<String, Object> payload = new HashMap<>(command.payload());
        payload.put("waitingTrainIds", selection.waitingIntents().stream()
            .map(TrainRouteIntent::trainId)
            .toList());
        payload.put("priorityScores", selection.priorityScores());
        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            payload,
            command.reason(),
            command.status(),
            command.createdAt(),
            command.appliedAt()
        );
    }

    private DispatchCommand commandFromRouteIntent(TrainRouteIntent intent) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("routeId", intent.routeId());
        payload.put("detail", intent.routeId());
        payload.put("simulationRunId", simulationRunId);
        payload.put("autoGenerated", true);
        payload.put("source", "AUTO_ROUTE_INTENT");
        payload.put("distanceToEntryMeters", intent.distanceToEntryMeters());
        payload.put("etaToEntrySeconds", intent.etaToEntrySeconds());
        payload.put("priorityScore", intent.priorityScore());
        payload.put("waitingSeconds", intent.waitingSeconds());
        payload.put("validUntil", intent.validUntil().toString());
        return new DispatchCommand(
            "DC-route-" + intent.trainId() + "-" + intent.routeId() + "-" + intent.createdAt().toEpochMilli(),
            intent.trainId(),
            "REQUEST_ROUTE",
            payload,
            intent.reason(),
            CommandStatus.PENDING,
            intent.createdAt(),
            null
        );
    }

    private List<DispatchCommand> trackGeneratedRouteCommands(List<DispatchCommand> commands) {
        if (commands.isEmpty()) {
            return commands;
        }
        List<DispatchCommand> tracked = new ArrayList<>();
        for (DispatchCommand command : commands) {
            if (RouteDispatchRecordStore.isRouteCommand(command)) {
                tracked.add(routeDispatchRecordStore.trackSubmittedRouteCommand(simulationRunId, command));
            } else {
                tracked.add(command);
            }
        }
        return tracked;
    }

    private List<DispatchCommand> suppressDuplicateDisturbanceCommands(List<DispatchCommand> generated) {
        if (generated == null || generated.isEmpty()) {
            return List.of();
        }
        Set<String> activeDisturbanceIds = new HashSet<>();
        for (DispatchCommand command : commandRecordStore.list(simulationRunId)) {
            String disturbanceId = payloadString(command, "disturbanceId");
            if (disturbanceId != null && isEffectTrackedStatus(command.status())) {
                activeDisturbanceIds.add(disturbanceId);
            }
        }
        List<DispatchCommand> filtered = new ArrayList<>();
        for (DispatchCommand command : generated) {
            String disturbanceId = payloadString(command, "disturbanceId");
            if (disturbanceId != null && !activeDisturbanceIds.add(disturbanceId)) {
                continue;
            }
            filtered.add(command);
        }
        return List.copyOf(filtered);
    }

    private void expirePendingRouteCommands(Instant simulatedAt) {
        List<String> expiredIds = routeDispatchRecordStore.expirePendingReservations(simulationRunId, simulatedAt);
        if (expiredIds.isEmpty()) {
            return;
        }
        Set<String> expiredIdSet = new HashSet<>(expiredIds);
        List<DispatchCommand> updated = new ArrayList<>();
        for (DispatchCommand command : commandRecordStore.list(simulationRunId)) {
            if (!expiredIdSet.contains(command.id()) || !isEffectTrackedStatus(command.status())) {
                continue;
            }
            DispatchCommand expired = commandWithStatus(command, CommandStatus.EXPIRED, simulatedAt);
            commandRecordStore.update(expired);
            replaceManualCommand(expired);
            updated.add(expired);
        }
        activeCommands = mergeActiveCommands(updated);
    }

    private void progressRequestedRouteTimeouts(Instant simulatedAt) {
        int timeoutSec = Math.max(1, properties.getRouteRequestTimeoutSeconds());
        List<DispatchCommand> updated = new ArrayList<>();
        for (RouteReservation reservation : routeDispatchRecordStore.listReservations(simulationRunId)) {
            if (!RouteReservationState.REQUESTED.equals(reservation.state()) || reservation.requestedAt() == null
                || reservation.requestedAt().plusSeconds(timeoutSec).isAfter(simulatedAt)) {
                continue;
            }
            String timedOutReservation = routeDispatchRecordStore.markRequestedTimeout(
                reservation.commandId(), simulatedAt);
            if (timedOutReservation == null) {
                continue;
            }
            for (DispatchCommand command : commandRecordStore.list(simulationRunId)) {
                if (reservation.commandId().equals(command.id()) && isEffectTrackedStatus(command.status())) {
                    DispatchCommand timeout = commandWithStatus(command, CommandStatus.TIMEOUT, simulatedAt);
                    commandRecordStore.update(timeout);
                    replaceManualCommand(timeout);
                    updated.add(timeout);
                    break;
                }
            }
        }
        if (!updated.isEmpty()) {
            activeCommands = mergeActiveCommands(updated);
        }
    }

    private List<DispatchCommand> automaticRouteCancellationCommands(Instant simulatedAt) {
        int holdTimeoutSec = Math.max(1, properties.getRouteEstablishedHoldTimeoutSeconds());
        List<DispatchCommand> commands = new ArrayList<>();
        for (RouteReservation reservation : routeDispatchRecordStore.listReservations(simulationRunId)) {
            if (!RouteReservationState.ACCEPTED.equals(reservation.state()) || reservation.acceptedAt() == null
                || reservation.cancelCommandId() != null
                || reservation.acceptedAt().plusSeconds(holdTimeoutSec).isAfter(simulatedAt)) {
                continue;
            }
            commands.add(createRouteCancellationCommand(reservation, simulatedAt, "ROUTE_HOLD_TIMEOUT"));
        }
        return List.copyOf(commands);
    }

    private DispatchCommand createRouteCancellationCommand(
        RouteReservation reservation,
        Instant simulatedAt,
        String reason
    ) {
        String commandId = "DC-cancel-route-" + reservation.reservationId() + "-" + simulatedAt.toEpochMilli();
        Map<String, Object> payload = new HashMap<>();
        payload.put("simulationRunId", simulationRunId);
        payload.put("routeId", reservation.routeId());
        payload.put("detail", reservation.routeId());
        payload.put("reservationId", reservation.reservationId());
        payload.put("originalRouteCommandId", reservation.commandId());
        payload.put("source", "DISPATCH_ROUTE_CLOSURE");
        routeDispatchRecordStore.linkCancellation(reservation.reservationId(), commandId, simulatedAt);
        return new DispatchCommand(
            commandId, reservation.trainId(), "CANCEL_ROUTE", payload, reason,
            CommandStatus.PENDING, simulatedAt, null
        );
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
                log.info(
                    "[DispatchLoop] command progressed {} -> {} {} {}",
                    command.status(),
                    progressed.status(),
                    commandSummary(progressed),
                    commandProgressDetail(progressed, profileByTrain.get(progressed.trainId()), simulatedAt)
                );
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
        if (!isTimeRegulationCommand(command) && isDisturbanceRecovered(command, disturbanceById)) {
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
            case "SHORTEN_DWELL" -> isTimeRegulationEffectConfirmed(command, profile);
            case "SPEED_BIAS" -> isSpeedBiasEffectConfirmed(command, train, profile);
            case "EXTEND_DWELL" -> isTimeRegulationEffectConfirmed(command, profile);
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
            case "EXTEND_DWELL" -> isDwelling(train)
                && isStopped(train)
                && dwellCommandAppliesAtCurrentStation(command, train);
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

    private String commandProgressDetail(DispatchCommand command, TrainRunProfile profile, Instant simulatedAt) {
        if (!"HEADWAY_ADJUST".equals(command.commandType()) && !"SPEED_BIAS".equals(command.commandType())) {
            return "";
        }
        int targetHeadway = payloadInt(command, "targetHeadwaySec", currentPlan.departureIntervalSec());
        int tolerance = payloadInt(command, "headwayToleranceSec", Math.max(5, currentPlan.departureIntervalSec() / 10));
        Double actual = profile == null ? null : profile.headwayActualSec();
        String violation = actual == null
            ? "unknown"
            : Math.round(Math.max(0, Math.abs(actual - targetHeadway) - tolerance)) + "s";
        double baselineError = payloadDouble(command, "baselineHeadwayErrorSec", Double.NaN);
        String baseline = Double.isNaN(baselineError) ? "-" : Math.round(baselineError) + "s";
        long elapsedSec = simulatedAt.getEpochSecond() - (
            command.appliedAt() == null ? command.createdAt() : command.appliedAt()
        ).getEpochSecond();
        return "headway actual=" + (actual == null ? "-" : Math.round(actual) + "s")
            + " target=" + targetHeadway + "s"
            + " tolerance=" + tolerance + "s"
            + " baselineError=" + baseline
            + " violation=" + violation
            + " observedFor=" + elapsedSec + "s";
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
        return isDwelling(train)
            && dwellCommandAppliesAtCurrentStation(command, train)
            && train.dwellElapsedSeconds() >= adjustedDwellTarget(delta);
    }

    private boolean isTimeRegulationCommand(DispatchCommand command) {
        return ("SHORTEN_DWELL".equals(command.commandType())
            || "EXTEND_DWELL".equals(command.commandType())
            || "SPEED_BIAS".equals(command.commandType()))
            && payloadString(command, "regulationAction") != null;
    }

    private boolean isTimeRegulationEffectConfirmed(DispatchCommand command, TrainRunProfile profile) {
        StationHeadwayObservation observation = trainRunMonitor.latestStationHeadway(command.trainId());
        if (observation != null && observation.departureAt().isAfter(command.createdAt())) {
            double baselineError = payloadDouble(command, "baselineHeadwayErrorSec", Double.NaN);
            double currentError = observation.headwayErrorSec();
            int tolerance = payloadInt(command, "headwayToleranceSec",
                Math.max(5, currentPlan.departureIntervalSec() / 10));
            if (Math.abs(currentError) <= tolerance) {
                return true;
            }
            if (!Double.isNaN(baselineError)) {
                double requiredImprovement = Math.max(
                    properties.getHeadwayEffectMinImprovementSec(),
                    Math.abs(baselineError) * properties.getHeadwayEffectImprovementRatio()
                );
                return Math.abs(baselineError) - Math.abs(currentError) >= requiredImprovement;
            }
        }
        if ("SCHEDULE_LATE".equals(payloadString(command, "headwayDirection")) && profile != null) {
            int baselineDelay = payloadInt(command, "baselineDepartureDelaySec", profile.departureDelaySec());
            return profile.lastDepartureAt() != null
                && profile.lastDepartureAt().isAfter(command.createdAt())
                && baselineDelay - profile.departureDelaySec() >= properties.getHeadwayEffectMinImprovementSec();
        }
        return false;
    }

    private boolean isSpeedBiasEffectConfirmed(
        DispatchCommand command,
        TrainState train,
        TrainRunProfile profile
    ) {
        if (isTimeRegulationEffectConfirmed(command, profile)) {
            return true;
        }
        String direction = payloadString(command, "headwayDirection");
        double baselineError = payloadDouble(command, "baselineHeadwayErrorSec", Double.NaN);
        if (profile != null && profile.headwayActualSec() != null && !Double.isNaN(baselineError)) {
            double currentError = profile.headwayActualSec() - payloadDouble(command, "targetHeadwaySec", currentPlan.departureIntervalSec());
            double requiredImprovement = Math.max(
                properties.getHeadwayEffectMinImprovementSec(),
                Math.abs(baselineError) * properties.getHeadwayEffectImprovementRatio()
            );
            if (Math.abs(baselineError) - Math.abs(currentError) >= requiredImprovement) {
                return true;
            }
        }
        double ratio = payloadDouble(command, "speedBiasRatio", 1.0);
        if ("TOO_SHORT".equals(direction) && ratio < 1.0) {
            return train.speedMetersPerSecond() <= properties.getBaseCruiseSpeedMps() * Math.min(0.95, ratio + 0.1);
        }
        if (("TOO_LONG".equals(direction) || "SCHEDULE_LATE".equals(direction)) && ratio > 1.0) {
            return hasDepartedOrMoving(train);
        }
        return false;
    }

    private boolean dwellCommandAppliesAtCurrentStation(DispatchCommand command, TrainState train) {
        String targetStationId = payloadString(command, "targetStationId");
        return targetStationId == null
            || targetStationId.isBlank()
            || targetStationId.equals(train.currentStationId());
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
        Instant start = CommandStatus.APPLIED.equals(command.status()) && command.appliedAt() != null
            ? command.appliedAt()
            : command.createdAt();
        long elapsedSec = simulatedAt.getEpochSecond() - start.getEpochSecond();
        return elapsedSec >= properties.getCommandEffectTimeoutSec();
    }

    private boolean requiresManualRelease(DispatchCommand command) {
        return "MANUAL".equals(command.reason())
            && ("SPEED_LIMIT".equals(command.commandType()) || "TEMP_SPEED_LIMIT".equals(command.commandType()));
    }

    private DispatchCommand commandWithStatus(DispatchCommand command, String status, Instant appliedAt) {
        Instant nextAppliedAt = command.appliedAt();
        if (nextAppliedAt == null && (CommandStatus.APPLIED.equals(status)
            || CommandStatus.EFFECT_CONFIRMED.equals(status)
            || CommandStatus.COMPLETED.equals(status))) {
            nextAppliedAt = appliedAt;
        }
        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            command.payload(),
            command.reason(),
            status,
            command.createdAt(),
            nextAppliedAt
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

    private void updateOperationPlanFromFeedback(DispatchCommand command, DispatchCommandFeedback feedback) {
        if (!"REQUEST_ROUTE".equals(command.commandType())) {
            return;
        }
        String planId = payloadString(command, "operationPlanId");
        if (planId == null || planId.isBlank()) {
            return;
        }
        boolean accepted = CommandStatus.APPLIED.equals(feedback.feedbackStatus())
            || CommandStatus.EFFECT_CONFIRMED.equals(feedback.feedbackStatus())
            || CommandStatus.COMPLETED.equals(feedback.feedbackStatus());
        boolean rejected = CommandStatus.SKIPPED.equals(feedback.feedbackStatus())
            || CommandStatus.CANCELLED.equals(feedback.feedbackStatus())
            || CommandStatus.TIMEOUT.equals(feedback.feedbackStatus());
        if (!accepted && !rejected) {
            return;
        }
        operationPlanningService.updateFromRouteFeedback(
            planId,
            accepted,
            accepted ? null : feedback.reason(),
            feedback.feedbackAt() == null ? Instant.now() : feedback.feedbackAt()
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
            && !CommandStatus.APPLIED.equals(currentStatus)
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

    public synchronized List<OperationRouteTemplate> operationRouteTemplates() {
        return operationPlanningService.templates();
    }

    public synchronized List<OperationRouteCandidate> previewOperationPlan(OperationPlanRequest request) {
        return operationPlanningService.preview(request);
    }

    public synchronized OperationPlan createOperationPlan(OperationPlanRequest request) {
        OperationPlan plan = operationPlanningService.create(
            simulationRunId,
            request,
            latestTrains,
            lastEvaluatedAt.equals(Instant.EPOCH) ? Instant.now() : lastEvaluatedAt
        );
        refreshSnapshot();
        return plan;
    }

    public synchronized OperationPlan cancelOperationPlan(String planId) {
        OperationPlan plan = operationPlanningService.cancel(
            planId,
            lastEvaluatedAt.equals(Instant.EPOCH) ? Instant.now() : lastEvaluatedAt
        );
        refreshSnapshot();
        return plan;
    }

    public synchronized List<OperationPlan> operationPlans() {
        return operationPlanningService.list(simulationRunId);
    }

    public synchronized SignalDispatchPlanPublication publishPlanToSignal(String operator, Instant effectiveFrom) {
        Map<String, DispatchRouteCandidate> signalRoutes = new HashMap<>();
        for (DispatchRouteCandidate route : routeCatalog.routes()) {
            signalRoutes.put(route.routeId(), route);
        }

        List<SignalDispatchPlanPublication.Entry> entries = new ArrayList<>();
        for (TrainServicePlan service : planLoader.services()) {
            String routeId = routeIdForService(service, signalRoutes);
            entries.add(publicationEntryFromService(service, routeId, signalRoutes.get(routeId)));
        }
        for (OperationPlan plan : operationPlanningService.list(simulationRunId)) {
            if (!"CANCELLED".equals(plan.status())) {
                entries.add(publicationEntryFromOperationPlan(plan, signalRoutes.get(plan.routeId())));
            }
        }

        int acceptedCount = (int) entries.stream()
            .filter(entry -> "ACCEPTED".equals(entry.status()))
            .count();
        int rejectedCount = entries.size() - acceptedCount;
        SignalDispatchPlanPublication publication = new SignalDispatchPlanPublication(
            "PUB-" + UUID.randomUUID().toString().substring(0, 8),
            simulationRunId,
            planLoader.planId(),
            planLoader.lineId(),
            effectiveFrom == null ? simulationStart : effectiveFrom,
            Instant.now(),
            operator == null || operator.isBlank() ? "dispatch" : operator.trim(),
            publicationStatus(acceptedCount, rejectedCount),
            acceptedCount,
            rejectedCount,
            entries
        );
        return signalDispatchPlanRegistry.accept(publication);
    }

    private SignalDispatchPlanPublication.Entry publicationEntryFromService(
        TrainServicePlan service,
        String routeId,
        DispatchRouteCandidate route
    ) {
        String resolvedRouteId = routeId == null || routeId.isBlank() ? "R_MAIN" : routeId;
        PlannedStop origin = service.origin();
        PlannedStop terminus = service.terminus();
        List<PlannedStop> plannedStops = planLoader.plannedStops(service, currentPlan);
        List<String> stationIds = plannedStops.stream()
            .map(PlannedStop::stationId)
            .toList();
        List<String> viaPointIds = stationIds.size() <= 2
            ? List.of()
            : List.copyOf(stationIds.subList(1, stationIds.size() - 1));
        Instant plannedDeparture = origin == null ? null : plannedDepartureAt(service);
        String rejectReason = null;
        if (route == null) {
            rejectReason = "routeId " + resolvedRouteId + " is not provided by signal route catalog";
        } else if (origin == null) {
            rejectReason = "service has no origin stop";
        }
        return new SignalDispatchPlanPublication.Entry(
            "SERVICE-" + service.serviceId(),
            "SERVICE_PLAN",
            service.serviceId(),
            service.trainId(),
            resolvedRouteId,
            route == null ? "主线" : route.name(),
            service.direction(),
            origin == null ? null : origin.stationId(),
            terminus == null ? null : terminus.stationId(),
            viaPointIds,
            stationIds,
            route == null ? List.of() : route.segmentIds(),
            plannedDeparture,
            rejectReason == null ? "ACCEPTED" : "REJECTED",
            rejectReason
        );
    }

    private String routeIdForService(TrainServicePlan service, Map<String, DispatchRouteCandidate> signalRoutes) {
        String direction = service == null || service.direction() == null
            ? ""
            : service.direction().trim().toUpperCase(java.util.Locale.ROOT);
        if ("UP".equals(direction) && signalRoutes.containsKey("R_UP")) {
            return "R_UP";
        }
        if ("DOWN".equals(direction) && signalRoutes.containsKey("R_DOWN")) {
            return "R_DOWN";
        }
        if (signalRoutes.containsKey("R_UP")) {
            return "R_UP";
        }
        if (signalRoutes.containsKey("R_MAIN")) {
            return "R_MAIN";
        }
        return signalRoutes.values().stream()
            .filter(DispatchRouteCandidate::mainline)
            .map(DispatchRouteCandidate::routeId)
            .findFirst()
            .orElse("R_MAIN");
    }

    private SignalDispatchPlanPublication.Entry publicationEntryFromOperationPlan(
        OperationPlan plan,
        DispatchRouteCandidate route
    ) {
        String rejectReason = route == null
            ? "routeId " + plan.routeId() + " is not provided by signal route catalog"
            : null;
        return new SignalDispatchPlanPublication.Entry(
            "OPERATION-" + plan.planId(),
            "OPERATION_PLAN",
            plan.planId(),
            plan.trainId(),
            plan.routeId(),
            route == null ? plan.routeName() : route.name(),
            plan.direction(),
            plan.originPointId(),
            plan.destinationPointId(),
            plan.viaPointIds(),
            plan.stationIds(),
            route == null ? plan.segmentIds() : route.segmentIds(),
            plan.plannedDepartureAt(),
            rejectReason == null ? "ACCEPTED" : "REJECTED",
            rejectReason
        );
    }

    private static String publicationStatus(int acceptedCount, int rejectedCount) {
        if (rejectedCount == 0) {
            return "ACCEPTED";
        }
        return acceptedCount == 0 ? "REJECTED" : "PARTIAL_ACCEPTED";
    }

    public synchronized String simulationRunId() {
        return simulationRunId;
    }

    public synchronized List<DisturbanceEvent> disturbances() {
        return disturbanceRecordStore.list(simulationRunId);
    }

    public synchronized DisturbanceEvent injectDemoDisturbance(
        String trainId,
        DisturbanceType type,
        String headwayDirection,
        Double targetHeadwaySec,
        Double actualHeadwaySec,
        Double violationSec,
        String stationId
    ) {
        String targetTrainId = trainId == null || trainId.isBlank()
            ? latestTrains.stream().findFirst().map(TrainState::id).orElse("TR-001")
            : trainId;
        TrainState train = latestTrains.stream()
            .filter(item -> targetTrainId.equals(item.id()))
            .findFirst()
            .orElse(null);
        String eventStationId = stationId == null || stationId.isBlank()
            ? train == null ? "" : train.currentStationId()
            : stationId;
        DisturbanceType eventType = type == null ? DisturbanceType.TRAIN_REGULATION : type;
        String direction = headwayDirection == null || headwayDirection.isBlank()
            ? "TOO_SHORT"
            : headwayDirection;
        double target = targetHeadwaySec == null || targetHeadwaySec <= 0
            ? currentPlan.departureIntervalSec()
            : targetHeadwaySec;
        double actual = actualHeadwaySec == null || actualHeadwaySec < 0
            ? demoActualHeadway(direction, target)
            : actualHeadwaySec;
        double tolerance = "TOO_LONG".equals(direction)
            ? Math.max(0, target * properties.getHeadwayExpandRatio() - target)
            : Math.max(0, target - target * properties.getHeadwayShrinkRatio());
        double violation = violationSec == null || violationSec < 0
            ? demoViolation(direction, target, actual)
            : violationSec;
        Instant recordedAt = lastEvaluatedAt.equals(Instant.EPOCH) ? Instant.now() : lastEvaluatedAt;
        DisturbanceEvent event = new DisturbanceEvent(
            "DIST-DEMO-" + UUID.randomUUID().toString().substring(0, 6),
            simulationRunId,
            targetTrainId,
            eventStationId,
            eventType,
            Math.max(0, violation),
            "OPEN",
            recordedAt,
            null,
            null,
            eventType == DisturbanceType.TRAIN_REGULATION || eventType == DisturbanceType.HEADWAY_VIOLATION
                ? direction
                : null,
            eventType == DisturbanceType.TRAIN_REGULATION || eventType == DisturbanceType.HEADWAY_VIOLATION
                ? target
                : null,
            eventType == DisturbanceType.TRAIN_REGULATION || eventType == DisturbanceType.HEADWAY_VIOLATION
                ? actual
                : null,
            eventType == DisturbanceType.TRAIN_REGULATION || eventType == DisturbanceType.HEADWAY_VIOLATION
                ? tolerance
                : null,
            eventType == DisturbanceType.TRAIN_REGULATION || eventType == DisturbanceType.HEADWAY_VIOLATION
                ? Math.max(0, violation)
                : null
        );
        DisturbanceEvent injected = disturbanceDetector.inject(event);
        disturbanceRecordStore.save(injected);
        refreshSnapshot();
        return injected;
    }

    public synchronized List<DispatchCommand> commands() {
        return commandRecordStore.list(simulationRunId);
    }

    private double demoActualHeadway(String direction, double target) {
        if ("TOO_LONG".equals(direction)) {
            return Math.round(target * Math.max(properties.getHeadwayExpandRatio() + 0.35, 1.65));
        }
        if ("SCHEDULE_LATE".equals(direction)) {
            return 0;
        }
        return Math.round(target * Math.max(0.2, properties.getHeadwayShrinkRatio() - 0.25));
    }

    private double demoViolation(String direction, double target, double actual) {
        if ("TOO_LONG".equals(direction)) {
            return Math.max(30, actual - target * properties.getHeadwayExpandRatio());
        }
        if ("SCHEDULE_LATE".equals(direction)) {
            return Math.max(30, properties.getDepartureDelaySec());
        }
        return Math.max(30, target * properties.getHeadwayShrinkRatio() - actual);
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
                    if (!dwellCommandAppliesAtCurrentStation(command, train)) {
                        sourceCommandIds.remove(command.id());
                        reasons.add(reason(command) + "(waitingForStation="
                            + payloadString(command, "targetStationId") + ")");
                        break;
                    }
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
            lastConstraintLogByTrain.remove(train.id());
            return DispatchConstraint.none(train.id());
        }
        DispatchConstraint constraint = new DispatchConstraint(
            train.id(),
            holdTrain,
            Math.max(0, Math.min(1, speedFactor)),
            targetSpeed,
            releaseStationStop && !holdTrain,
            reasons.isEmpty() ? "NORMAL" : String.join("; ", reasons),
            sourceCommandIds
        );
        if (!sourceCommandIds.isEmpty()) {
            if (shouldLogConstraint(constraint)) {
                log.info(
                    "[DispatchLoop] constraint train={} hold={} speedFactor={} targetSpeed={} releaseStationStop={} commands={} reason={}",
                    constraint.trainId(),
                    constraint.holdTrain(),
                    String.format("%.2f", constraint.speedFactor()),
                    constraint.targetSpeedMetersPerSecond(),
                    constraint.releaseStationStop(),
                    constraint.sourceCommandIds(),
                    constraint.reason()
                );
            }
        } else {
            lastConstraintLogByTrain.remove(train.id());
            if (constraint.holdTrain() || constraint.releaseStationStop() || constraint.targetSpeedMetersPerSecond() != null) {
                log.debug(
                    "[DispatchLoop] automatic constraint train={} hold={} speedFactor={} targetSpeed={} releaseStationStop={} reason={}",
                    constraint.trainId(),
                    constraint.holdTrain(),
                    String.format("%.2f", constraint.speedFactor()),
                    constraint.targetSpeedMetersPerSecond(),
                    constraint.releaseStationStop(),
                    constraint.reason()
                );
            }
        }
        return constraint;
    }

    private boolean shouldLogConstraint(DispatchConstraint constraint) {
        String key = constraint.holdTrain()
            + "|"
            + String.format("%.2f", constraint.speedFactor())
            + "|"
            + constraint.targetSpeedMetersPerSecond()
            + "|"
            + constraint.releaseStationStop()
            + "|"
            + constraint.sourceCommandIds()
            + "|"
            + constraint.reason();
        String previous = lastConstraintLogByTrain.put(constraint.trainId(), key);
        return !key.equals(previous);
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

    private void logCommandBatch(String phase, List<DispatchCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (DispatchCommand command : commands) {
            log.info("[DispatchLoop] command {} {}", phase, commandSummary(command));
        }
    }

    private void logDisturbanceCreated(DisturbanceEvent event) {
        if ("HEADWAY_VIOLATION".equals(event.disturbanceType().name())) {
            log.info(
                "[DispatchLoop] headway violation created id={} train={} direction={} actual={}s target={}s tolerance={}s violation={}s",
                event.id(),
                event.trainId(),
                event.headwayDirection(),
                formatSeconds(event.actualHeadwaySec()),
                formatSeconds(event.targetHeadwaySec()),
                formatSeconds(event.toleranceSec()),
                formatSeconds(event.violationSec())
            );
            return;
        }
        log.info(
            "[DispatchLoop] disturbance created id={} train={} type={} station={} deviation={}",
            event.id(),
            event.trainId(),
            event.disturbanceType(),
            event.stationId(),
            event.deviationValue()
        );
    }

    private String formatSeconds(Double value) {
        return value == null ? "-" : String.format("%.0f", value);
    }

    private String commandSummary(DispatchCommand command) {
        if (command == null) {
            return "null";
        }
        return "id=" + command.id()
            + " train=" + command.trainId()
            + " type=" + command.commandType()
            + " status=" + command.status()
            + " reason=" + command.reason()
            + " payload=" + command.payload();
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
                profile.trainId(),
                profile.frontTrainId(),
                profile.headwayActualSec(),
                profile.headwayActualSec() == null ? null : (double) profile.headwayDeviationSec(),
                profile.headwayDeviationSec(),
                profile.headwayState(),
                profile.headwayAction(),
                profile.headwayAction(),
                regulationReason(profile),
                profile.dwellDeviationSec(),
                profile.departureDelaySec()
            ))
            .toList();
        List<DispatchSnapshot.DisturbanceView> disturbanceViews = disturbances.stream()
            .filter(event -> "OPEN".equals(event.status())
                || "HANDLED".equals(event.status())
                || "RECOVERED".equals(event.status()))
            .map(event -> new DispatchSnapshot.DisturbanceView(
                event.id(),
                event.trainId(),
                event.trainId(),
                event.stationId(),
                event.disturbanceType().name(),
                regulationAction(event),
                event.deviationValue(),
                event.headwayDirection(),
                event.targetHeadwaySec(),
                event.actualHeadwaySec(),
                event.toleranceSec(),
                event.violationSec(),
                event.status()
            ))
            .toList();
        List<DispatchSnapshot.CommandView> commandViews = commands.stream()
            .map(command -> new DispatchSnapshot.CommandView(
                command.id(),
                command.trainId(),
                payloadString(command, "regulatedTrainId") == null
                    ? command.trainId()
                    : payloadString(command, "regulatedTrainId"),
                command.commandType(),
                command.status(),
                command.reason(),
                payloadString(command, "regulationAction"),
                command.payload(),
                command.createdAt(),
                command.appliedAt()
            ))
            .toList();
        Map<String, DispatchCommand> commandById = new HashMap<>();
        for (DispatchCommand command : commands) {
            commandById.put(command.id(), command);
        }
        List<DispatchSnapshot.ServicePlanView> serviceViews = planLoader.services().stream()
            .map(service -> {
                PlannedStop origin = service.origin();
                PlannedStop terminus = service.terminus();
                String commandId = departureCommandIdByService.get(service.serviceId());
                DispatchCommand command = commandId == null ? null : commandById.get(commandId);
                return new DispatchSnapshot.ServicePlanView(
                    service.serviceId(),
                    service.circulationId(),
                    service.trainId(),
                    origin == null ? null : origin.stationId(),
                    terminus == null ? null : terminus.stationId(),
                    origin == null ? null : plannedDepartureAt(service),
                    command == null ? "PLANNED" : command.status(),
                    commandId
                );
            })
            .toList();
        List<DispatchSnapshot.StationHeadwayView> stationHeadwayViews = trainRunMonitor.latestStationHeadways().stream()
            .map(observation -> new DispatchSnapshot.StationHeadwayView(
                observation.stationId(),
                observation.direction(),
                observation.trainId(),
                observation.frontTrainId(),
                observation.departureAt(),
                observation.targetHeadwaySec(),
                observation.actualHeadwaySec(),
                observation.headwayErrorSec(),
                observation.state(),
                observation.regulationAction()
            ))
            .toList();
        boolean interventionActive = !disturbanceViews.isEmpty() || commandViews.stream()
            .anyMatch(command -> isActiveCommandStatus(command.status()));
        List<DispatchSnapshot.RouteDecisionView> routeDecisionViews =
            routeDispatchRecordStore.visibleDecisions(simulationRunId, 12).stream()
                .map(this::routeDecisionView)
                .toList();
        List<DispatchSnapshot.RouteReservationView> routeReservationViews =
            routeDispatchRecordStore.visibleReservations(simulationRunId, 12).stream()
                .map(this::routeReservationView)
                .toList();
        boolean routeDispatchActive = routeDecisionViews.stream()
            .anyMatch(decision -> isActiveRouteDecisionStatus(decision.status()));
        List<DispatchSnapshot.OperationPlanView> operationPlanViews = operationPlanningService.list(simulationRunId).stream()
            .map(this::operationPlanView)
            .toList();
        return new DispatchSnapshot(
            plan.periodType(),
            plan.planId(),
            plan.departureIntervalSec(),
            plan.defaultDwellTimeSec(),
            serviceViews,
            stationHeadwayViews,
            interventionActive,
            trainViews,
            disturbanceViews,
            commandViews,
            routeDispatchActive,
            routeDecisionViews,
            routeReservationViews,
            operationPlanViews,
            lineRegulationPlanView(latestLineRegulationPlan)
        );
    }

    private Instant plannedDepartureAt(TrainServicePlan service) {
        return simulationStart.plusSeconds(planLoader.plannedDepartureOffsetSec(service, currentPlan));
    }

    private DispatchSnapshot.LineRegulationPlanView lineRegulationPlanView(LineRegulationPlan plan) {
        if (plan == null) {
            return DispatchSnapshot.LineRegulationPlanView.empty();
        }
        return new DispatchSnapshot.LineRegulationPlanView(
            plan.planId(),
            plan.generatedAt(),
            plan.objective(),
            plan.status(),
            plan.targetHeadwaySec(),
            plan.currentMaxAbsHeadwayErrorSec(),
            plan.predictedMaxAbsHeadwayErrorSec(),
            plan.commandCount(),
            plan.decisions().stream()
                .map(decision -> new DispatchSnapshot.LineRegulationDecisionView(
                    decision.trainId(),
                    decision.regulatedTrainId(),
                    decision.frontTrainId(),
                    decision.action(),
                    decision.commandType(),
                    decision.status(),
                    decision.reason(),
                    decision.currentHeadwaySec(),
                    decision.targetHeadwaySec(),
                    decision.currentHeadwayErrorSec(),
                    decision.predictedHeadwayErrorSec(),
                    decision.priorityScore(),
                    decision.signalConstraint(),
                    decision.commandId()
                ))
                .toList()
        );
    }

    private DispatchSnapshot.OperationPlanView operationPlanView(OperationPlan plan) {
        return new DispatchSnapshot.OperationPlanView(
            plan.planId(),
            plan.routeId(),
            plan.routeName(),
            plan.direction(),
            plan.trainId(),
            plan.originPointId(),
            plan.destinationPointId(),
            plan.viaPointIds(),
            plan.pointIds(),
            plan.stationIds(),
            plan.segmentIds(),
            plan.plannedDepartureAt(),
            plan.status(),
            plan.priority(),
            plan.version(),
            plan.routeCommandId(),
            plan.rejectReason()
        );
    }

    private DispatchSnapshot.RouteDecisionView routeDecisionView(RouteDispatchDecision decision) {
        return new DispatchSnapshot.RouteDecisionView(
            decision.decisionId(),
            decision.selectedTrainId(),
            decision.selectedRouteId(),
            decision.waitingTrainIds(),
            decision.priorityScores(),
            routeDecisionWaitingSeconds(decision),
            decision.status(),
            decision.routeCommandId(),
            decision.reason(),
            decision.rejectReason()
        );
    }

    private double routeDecisionWaitingSeconds(RouteDispatchDecision decision) {
        return commandRecordStore.list(simulationRunId).stream()
            .filter(command -> decision.routeCommandId().equals(command.id()))
            .findFirst()
            .map(command -> payloadDouble(command, "waitingSeconds", 0))
            .orElse(0.0);
    }

    private String regulationReason(TrainRunProfile profile) {
        return switch (profile.headwayAction()) {
            case TrainRegulationAction.CATCH_UP -> profile.headwayActualSec() == null
                ? "SCHEDULE_DELAY_RECOVERY"
                : "HEADWAY_TOO_LONG";
            case TrainRegulationAction.SLOW_DOWN -> "HEADWAY_TOO_SHORT";
            case TrainRegulationAction.NORMAL -> "HEADWAY_ON_TARGET";
            default -> "WAITING_FOR_REFERENCE_DATA";
        };
    }

    private String regulationAction(DisturbanceEvent event) {
        if ("TOO_SHORT".equals(event.headwayDirection())) {
            return TrainRegulationAction.SLOW_DOWN;
        }
        if ("TOO_LONG".equals(event.headwayDirection())
            || "SCHEDULE_LATE".equals(event.headwayDirection())
            || event.disturbanceType() == com.railwaysim.dispatch.disturbance.DisturbanceType.DEPARTURE_DELAY
            || event.disturbanceType() == com.railwaysim.dispatch.disturbance.DisturbanceType.DWELL_EXTENDED) {
            return TrainRegulationAction.CATCH_UP;
        }
        return TrainRegulationAction.OBSERVE;
    }

    private DispatchSnapshot.RouteReservationView routeReservationView(RouteReservation reservation) {
        return new DispatchSnapshot.RouteReservationView(
            reservation.reservationId(),
            reservation.trainId(),
            reservation.routeId(),
            reservation.decisionId(),
            reservation.state(),
            reservation.commandId(),
            reservation.rejectReason(),
            reservation.failureCode(),
            reservation.failureCategory(),
            reservation.retryable(),
            reservation.retryCount(),
            reservation.expiresAt(),
            reservation.retryable()
                ? reservation.updatedAt().plusSeconds(Math.max(0, properties.getRouteRequestCooldownSeconds()))
                : null,
            reservation.timedOutAt(),
            reservation.cancelCommandId()
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

    private boolean isActiveRouteDecisionStatus(String status) {
        return "REQUESTED".equals(status) || "ACCEPTED".equals(status);
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
