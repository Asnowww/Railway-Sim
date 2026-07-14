package com.railwaysim.simulation;

import com.railwaysim.api.SimulationWebSocketHandler;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchCommandFeedback;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.integration.DispatchCommandPublisher;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.monitor.MonitorService;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.simulation.event.DomainEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import com.railwaysim.vehicle.runtime.VehicleRuntimeStepResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SimulationRuntime {

    private static final Logger log = LoggerFactory.getLogger(SimulationRuntime.class);

    private final TrainManager trainManager;
    private final TrackService trackService;
    private final SignalService signalService;
    private final PowerService powerService;
    private final DispatchService dispatchService;
    private final DispatchCommandPublisher dispatchCommandPublisher;
    private final MonitorService monitorService;
    private final SimulationWebSocketHandler webSocketHandler;
    private final SimulationProperties simulationProperties;
    private final SimpleEventBus eventBus;
    private final RealtimeStateCache realtimeStateCache;
    private final SimulationPersistenceService persistenceService;
    private final RouteInterlockingService interlockingService;
    private final VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService;
    private final SimulationRunService simulationRunService;
    private final SimulationRunContext simulationRunContext;
    private final TrainStopEvaluationService trainStopEvaluationService;
    private final FinalControlDecisionPersistenceService finalControlDecisionPersistenceService;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private List<DomainEvent> lastEvents = List.of();
    private long tick;
    private SimulationStatus status = SimulationStatus.STOPPED;
    private Instant simulatedTime = Instant.now();
    private long lastPushAtMillis;
    private final Set<String> appliedFeedbackSent = new HashSet<>();
    private volatile SimulationTickTiming latestTickTiming = SimulationTickTiming.idle();

    public SimulationRuntime(
        TrainManager trainManager,
        TrackService trackService,
        SignalService signalService,
        PowerService powerService,
        DispatchService dispatchService,
        DispatchCommandPublisher dispatchCommandPublisher,
        MonitorService monitorService,
        SimulationWebSocketHandler webSocketHandler,
        SimulationProperties simulationProperties,
        SimpleEventBus eventBus,
        RealtimeStateCache realtimeStateCache,
        SimulationPersistenceService persistenceService,
        RouteInterlockingService interlockingService,
        VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService,
        SimulationRunService simulationRunService,
        SimulationRunContext simulationRunContext,
        TrainStopEvaluationService trainStopEvaluationService,
        FinalControlDecisionPersistenceService finalControlDecisionPersistenceService,
        StaticInfrastructureCatalog infrastructureCatalog
    ) {
        this.trainManager = trainManager;
        this.trackService = trackService;
        this.signalService = signalService;
        this.powerService = powerService;
        this.dispatchService = dispatchService;
        this.dispatchCommandPublisher = dispatchCommandPublisher;
        this.monitorService = monitorService;
        this.webSocketHandler = webSocketHandler;
        this.simulationProperties = simulationProperties;
        this.eventBus = eventBus;
        this.realtimeStateCache = realtimeStateCache;
        this.persistenceService = persistenceService;
        this.interlockingService = interlockingService;
        this.vehicleRuntimeIntegrationService = vehicleRuntimeIntegrationService;
        this.simulationRunService = simulationRunService;
        this.simulationRunContext = simulationRunContext;
        this.trainStopEvaluationService = trainStopEvaluationService;
        this.finalControlDecisionPersistenceService = finalControlDecisionPersistenceService;
        this.infrastructureCatalog = infrastructureCatalog;
        this.simulationRunContext.update(dispatchService.simulationRunId(), tick);
    }

    public synchronized SimulationSnapshot snapshot() {
        return buildSnapshot();
    }

    public SimulationTickTiming latestTickTiming() {
        return latestTickTiming;
    }

    public synchronized SimulationSnapshot start() {
        simulationRunService.start(dispatchService.simulationRunId(), tick, simulatedTime);
        status = SimulationStatus.RUNNING;
        return advanceWithFailureTracking();
    }

    public synchronized SimulationSnapshot tick() {
        if (status == SimulationStatus.STOPPED) {
            simulationRunService.start(dispatchService.simulationRunId(), tick, simulatedTime);
            status = SimulationStatus.RUNNING;
        }
        return advanceWithFailureTracking();
    }

    /**
     * Advances the shared simulation clock only while the runtime is running.
     * Browser clients remain read-only, so opening more pages cannot accelerate time.
     */
    public synchronized void advanceScheduledTick() {
        if (status == SimulationStatus.RUNNING) {
            advanceWithFailureTracking();
        }
    }

    public synchronized SimulationSnapshot pause() {
        status = SimulationStatus.PAUSED;
        simulationRunService.pause(dispatchService.simulationRunId(), tick);
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot stop() {
        status = SimulationStatus.STOPPED;
        simulationRunService.complete(dispatchService.simulationRunId(), tick, simulatedTime, "STOPPED_BY_API");
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot reset() {
        String previousRunId = dispatchService.simulationRunId();
        long previousTick = tick;
        Instant previousSimulatedTime = simulatedTime;
        dispatchService.reset();
        String nextRunId = dispatchService.simulationRunId();
        simulationRunService.rollover(
            previousRunId, nextRunId, previousTick, previousSimulatedTime, Instant.now());
        tick = 0;
        status = SimulationStatus.STOPPED;
        simulatedTime = Instant.now();
        lastPushAtMillis = 0;
        simulationRunContext.update(dispatchService.simulationRunId(), tick);
        trainManager.reset();
        trainStopEvaluationService.resetTransientState();
        trackService.reset();
        signalService.reset();
        interlockingService.reset();
        powerService.reset();
        realtimeStateCache.clear();
        eventBus.drain();
        lastEvents = List.of();
        appliedFeedbackSent.clear();
        return buildSnapshot();
    }

    private SimulationSnapshot advanceOneTick() {
        long tickStartedAt = System.nanoTime();
        tick++;
        simulatedTime = simulatedTime.plusMillis(simulationProperties.getTickMillis());
        TickContext context = new TickContext(
            tick,
            simulationProperties.getTickMillis(),
            simulationProperties.getTickMillis() / 1000.0,
            simulatedTime,
            dispatchService.simulationRunId()
        );
        simulationRunContext.update(context.simulationRunId(), context.tick());

        List<TrainState> beforeTrainStates = trainManager.states();
        trackService.updateOccupancy(beforeTrainStates);
        List<TrackConstraint> trackConstraints = trackService.constraintsForTrains(beforeTrainStates);
        long trackConstraintsCompletedAt = System.nanoTime();
        signalService.calculateAuthorities(beforeTrainStates, trackConstraints, List.of());
        long preliminarySignalAndDispatchCompletedAt = System.nanoTime();
        dispatchService.evaluate(context, beforeTrainStates, signalService.authorities());

        // 一次性进路指令在约束计算前交给联锁处理。
        List<DispatchCommand> routeCommands = new ArrayList<>(dispatchService.drainCommandsOfType("REROUTE"));
        routeCommands.addAll(dispatchService.drainCommandsOfType("REQUEST_ROUTE"));
        routeCommands.addAll(dispatchService.drainCommandsOfType("CANCEL_ROUTE"));
        List<DispatchCommandFeedback> routeFeedbacks = new ArrayList<>();
        for (DispatchCommand cmd : routeCommands) {
            RouteInterlockingService.DispatchRouteRequest routeRequest = dispatchRouteRequest(cmd);
            var result = interlockingService.applyDispatchCommand(routeRequest);
            if (!result.accepted()) {
                log.warn("[Runtime] 联锁拒绝调度指令 {}: {}", cmd.id(), result.rejectReason());
            }
            String routeId = routeRequest.routeId() == null || routeRequest.routeId().isBlank()
                ? commandDetail(cmd)
                : routeRequest.routeId();
            Map<String, Object> feedbackDetails = new java.util.LinkedHashMap<>();
            feedbackDetails.put("accepted", result.accepted());
            feedbackDetails.put("routeId", routeId);
            if (routeRequest.operationPlanId() != null && !routeRequest.operationPlanId().isBlank()) {
                feedbackDetails.put("operationPlanId", routeRequest.operationPlanId());
            }
            if (routeRequest.direction() != null && !routeRequest.direction().isBlank()) {
                feedbackDetails.put("direction", routeRequest.direction());
            }
            if (routeRequest.originPointId() != null && !routeRequest.originPointId().isBlank()) {
                feedbackDetails.put("originPointId", routeRequest.originPointId());
            }
            if (routeRequest.destinationPointId() != null && !routeRequest.destinationPointId().isBlank()) {
                feedbackDetails.put("destinationPointId", routeRequest.destinationPointId());
            }
            if (!routeRequest.viaPointIds().isEmpty()) {
                feedbackDetails.put("viaPointIds", routeRequest.viaPointIds());
            }
            if (!routeRequest.segmentIds().isEmpty()) {
                feedbackDetails.put("segmentIds", routeRequest.segmentIds());
            }
            feedbackDetails.put("resultCode", result.accepted()
                ? ("CANCEL_ROUTE".equals(cmd.commandType()) ? "ROUTE_CANCELLED" : "ROUTE_ESTABLISHED")
                : "INTERLOCKING_REJECTED");
            feedbackDetails.put("failureCode", result.failureCode().name());
            feedbackDetails.put("retryable", result.retryable());
            if (result.rejectReason() != null) {
                feedbackDetails.put("rawReason", result.rejectReason());
            }
            var routeState = interlockingService.state(routeId);
            if (routeState != null && routeState.status() != null) {
                feedbackDetails.put("interlockingState", routeState.status().name());
            }
            routeFeedbacks.add(new DispatchCommandFeedback(
                cmd.id(),
                cmd.trainId(),
                cmd.commandType(),
                "SIGNAL_INTERLOCKING",
                result.accepted() ? CommandStatus.EFFECT_CONFIRMED : CommandStatus.SKIPPED,
                result.accepted() ? "route established" : result.rejectReason(),
                context.simulatedTime(),
                feedbackDetails
            ));
        }
        if (!routeFeedbacks.isEmpty()) {
            dispatchService.acceptFeedback(routeFeedbacks);
        }

        // 发车指令 — 调度→信号→车辆：联锁检查进路→创建列车→列车进场自动建进路
        handleDepartures(dispatchService.drainCommandsOfType("DEPART"));

        List<DispatchCommand> generatedCommands = dispatchService.drainCommands();
        if (!generatedCommands.isEmpty()) {
            dispatchCommandPublisher.publish(generatedCommands);
        }
        long commandAndInterlockingCompletedAt = System.nanoTime();

        // 保存调度约束快照（消费前），供完成检查使用
        List<DispatchConstraint> dispatchConstraints = dispatchService.constraintsForTrains(beforeTrainStates);
        // constraintsForTrains returns the pre-consumption values; preserve that
        // same snapshot for completion checks instead of calculating all
        // automatic headway regulations a second time.
        List<DispatchConstraint> dispatchConstraintsPreview = List.copyOf(dispatchConstraints);
        signalService.calculateAuthorities(beforeTrainStates, trackConstraints, dispatchConstraints);
        dispatchService.syncRouteReservations(interlockingService.states(), context.simulatedTime());
        boolean externalPowerAuthority = vehicleRuntimeIntegrationService.usesExternalPowerAuthority();
        if (externalPowerAuthority) {
            // 9200 must be ready before 9300 performs its first authoritative power query.
            powerService.prepareExternalNetwork();
        }
        List<PowerConstraint> powerConstraints = externalPowerAuthority
            ? List.of()
            : powerService.constraintsForTrains(beforeTrainStates);
        long constraintsCompletedAt = System.nanoTime();

        VehicleRuntimeStepResult trainSteps = trainManager.tickAll(
            context,
            signalService.authorities(),
            trackConstraints,
            dispatchConstraints,
            powerConstraints
        );
        long vehicleRuntimeCompletedAt = System.nanoTime();
        List<VehiclePhysicsOutput> outputs = trainSteps.outputs();
        finalControlDecisionPersistenceService.persistFinalControlDecisions(context, trainSteps.trainSteps());
        trainStopEvaluationService.evaluate(context, trainManager.states());
        long centralVehiclePostProcessingCompletedAt = System.nanoTime();
        powerService.updateFromVehicleOutputs(outputs);
        long powerSnapshotCompletedAt = System.nanoTime();
        trackService.updateOccupancy(trainManager.states());
        signalService.recomputeSignalAspects(); // 基于最终区段占用刷新灯色
        generateTimeCommandFeedback(dispatchConstraints, signalService.authorities()); // 时间命令→调度反馈
        checkDispatchCompletion(dispatchConstraintsPreview); // 信号→调度反馈：指令是否完成
        lastEvents = eventBus.drain();
        persistIfDue(context);
        long centralPostProcessingCompletedAt = System.nanoTime();

        SimulationSnapshot snapshot = buildSnapshot();
        long snapshotCompletedAt = System.nanoTime();
        var monitorTiming = monitorService.latestBuildTiming();
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastPushAtMillis >= simulationProperties.getPushIntervalMillis()) {
            webSocketHandler.broadcast(snapshot);
            lastPushAtMillis = nowMillis;
        }
        long tickCompletedAt = System.nanoTime();
        latestTickTiming = new SimulationTickTiming(
            context.tick(),
            beforeTrainStates.size(),
            externalPowerAuthority,
            elapsedMillis(tickStartedAt, trackConstraintsCompletedAt),
            elapsedMillis(trackConstraintsCompletedAt, preliminarySignalAndDispatchCompletedAt),
            elapsedMillis(preliminarySignalAndDispatchCompletedAt, commandAndInterlockingCompletedAt),
            elapsedMillis(commandAndInterlockingCompletedAt, constraintsCompletedAt),
            elapsedMillis(tickStartedAt, constraintsCompletedAt),
            elapsedMillis(constraintsCompletedAt, vehicleRuntimeCompletedAt),
            elapsedMillis(vehicleRuntimeCompletedAt, centralVehiclePostProcessingCompletedAt),
            elapsedMillis(centralVehiclePostProcessingCompletedAt, powerSnapshotCompletedAt),
            elapsedMillis(powerSnapshotCompletedAt, centralPostProcessingCompletedAt),
            monitorTiming.serviceHealthMillis(),
            monitorTiming.alarmProjectionMillis(),
            monitorTiming.alarmReconciliationMillis(),
            elapsedMillis(centralPostProcessingCompletedAt, snapshotCompletedAt),
            elapsedMillis(snapshotCompletedAt, tickCompletedAt),
            elapsedMillis(tickStartedAt, tickCompletedAt)
        );
        return snapshot;
    }

    private double elapsedMillis(long startedAt, long completedAt) {
        return (completedAt - startedAt) / 1_000_000.0;
    }

    private SimulationSnapshot advanceWithFailureTracking() {
        try {
            return advanceOneTick();
        } catch (RuntimeException ex) {
            status = SimulationStatus.STOPPED;
            simulationRunService.fail(
                dispatchService.simulationRunId(), tick, simulatedTime,
                ex.getClass().getSimpleName() + ":" + (ex.getMessage() == null ? "" : ex.getMessage()));
            try {
                // 即使本 tick 中止，也要把外部服务的 FALLBACK/STALE 状态写入恢复门禁与持久化记录。
                buildSnapshot();
            } catch (RuntimeException monitorException) {
                log.warn("[Runtime] failed to record service health after tick failure", monitorException);
            }
            throw ex;
        }
    }

    private String commandDetail(DispatchCommand command) {
        if (command.payload() != null) {
            Object routeId = command.payload().get("routeId");
            if (routeId != null) {
                return routeId.toString();
            }
            Object detail = command.payload().get("detail");
            if (detail != null) {
                return detail.toString();
            }
        }
        return command.reason();
    }

    private RouteInterlockingService.DispatchRouteRequest dispatchRouteRequest(DispatchCommand command) {
        Map<String, Object> payload = command.payload() == null ? Map.of() : command.payload();
        return new RouteInterlockingService.DispatchRouteRequest(
            command.id(),
            command.commandType(),
            commandDetail(command),
            command.trainId(),
            stringFromPayload(payload, "operationPlanId", null),
            stringFromPayload(payload, "routeId", null),
            stringFromPayload(payload, "direction", null),
            stringFromPayload(payload, "originPointId", null),
            stringFromPayload(payload, "destinationPointId", null),
            stringListFromPayload(payload, "viaPointIds"),
            stringListFromPayload(payload, "segmentIds")
        );
    }

    private static List<String> stringListFromPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(item -> item != null)
            .map(Object::toString)
            .toList();
    }

    /**
     * 信号→调度反馈：检查调度约束是否已作用到信号/车辆链路。
     *
     * <p>只反馈带 sourceCommandIds 的真实调度指令；运行图自动约束只参与控制，不伪造成调度指令。
     */
    private void checkDispatchCompletion(List<DispatchConstraint> constraints) {
        List<DispatchCommandFeedback> feedbacks = new ArrayList<>();
        List<TrainState> trains = trainManager.states();
        for (DispatchConstraint constraint : constraints) {
            if (constraint.sourceCommandIds().isEmpty()) {
                continue;
            }
            TrainState train = trains.stream()
                .filter(t -> t.id().equals(constraint.trainId()))
                .findFirst().orElse(null);
            if (train == null) continue;

            boolean done = false;
            if (constraint.holdTrain() && train.zeroSpeed()) {
                done = true;
            } else if (constraint.speedFactor() < 1.0 && constraint.speedFactor() > 0) {
                done = train.speedMetersPerSecond() <= constraint.applyToSpeedLimit(99) + 0.5;
            } else if (constraint.targetSpeedMetersPerSecond() != null) {
                done = Math.abs(train.speedMetersPerSecond() - constraint.targetSpeedMetersPerSecond()) < 0.5;
            }

            if (done) {
                for (String commandId : constraint.sourceCommandIds()) {
                    if (!appliedFeedbackSent.add(commandId)) {
                        continue;
                    }
                    log.info(
                        "[DispatchLoop] runtime feedback command={} train={} status={} speed={} zeroSpeed={} reason={}",
                        commandId,
                        train.id(),
                        CommandStatus.APPLIED,
                        String.format("%.2f", train.speedMetersPerSecond()),
                        train.zeroSpeed(),
                        constraint.reason()
                    );
                    // 查找该列车的 MA 以增强反馈
                    MovementAuthority trainMa = signalService.authorities().stream()
                        .filter(a -> a.trainId().equals(train.id()))
                        .findFirst().orElse(null);
                    Map<String, Object> detail = new java.util.LinkedHashMap<>();
                    detail.put("actualSpeedMps", Math.round(train.speedMetersPerSecond() * 100.0) / 100.0);
                    detail.put("zeroSpeed", train.zeroSpeed());
                    detail.put("constraintReason", constraint.reason());
                    detail.put("requestedSpeedBiasRatio", Math.round(constraint.speedFactor() * 1000.0) / 1000.0);
                    if (trainMa != null) {
                        detail.put("effectiveSpeedLimitMps", Math.round(trainMa.speedLimitMetersPerSecond() * 100.0) / 100.0);
                        detail.put("movementAuthorityEndMeters", Math.round(trainMa.authorityEndMeters() * 10.0) / 10.0);
                        detail.put("currentSegmentId", trainMa.currentSegmentId());
                        detail.put("maReason", trainMa.reason());
                        if (trainMa.reasonCode() != null) detail.put("reasonCode", trainMa.reasonCode());
                    }
                    detail.put("finalLimitSource", deriveConstraintSource(trainMa, constraint));
                    feedbacks.add(new DispatchCommandFeedback(
                        commandId,
                        train.id(),
                        null,
                        "SIGNAL_RUNTIME",
                        CommandStatus.APPLIED,
                        constraint.reason(),
                        Instant.now(),
                        detail
                    ));
                }
            }
        }
        if (!feedbacks.isEmpty()) {
            dispatchService.acceptFeedback(feedbacks);
            log.info("[Runtime] 信号回执：{} 条调度指令已作用", feedbacks.size());
        }
    }

    /**
     * 生成时间调度命令（SPEED_BIAS/EXTEND_DWELL等）的信号侧应用反馈。
     * 每 tick 在 MA 计算和应用后，逐条记录命令的信号侧效果。
     */
    private void generateTimeCommandFeedback(
        List<DispatchConstraint> constraints,
        List<MovementAuthority> authorities
    ) {
        List<DispatchCommandFeedback> feedbacks = new ArrayList<>();
        Map<String, MovementAuthority> authorityByTrain = new HashMap<>();
        for (MovementAuthority ma : authorities) {
            authorityByTrain.put(ma.trainId(), ma);
        }
        Map<String, TrainState> trainById = new HashMap<>();
        for (TrainState t : trainManager.states()) {
            trainById.put(t.id(), t);
        }

        for (DispatchConstraint constraint : constraints) {
            if (constraint.sourceCommandIds().isEmpty()) continue;
            MovementAuthority ma = authorityByTrain.get(constraint.trainId());
            TrainState train = trainById.get(constraint.trainId());
            if (train == null || ma == null) continue;

            double trainSpeed = train.speedMetersPerSecond();
            double maEnd = ma.authorityEndMeters();
            double trainHead = train.positionMeters();
            double maDistance = Math.max(0, maEnd - trainHead);
            String constraintSource = deriveConstraintSource(ma, constraint);

            for (String commandId : constraint.sourceCommandIds()) {
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("accepted", true);
                details.put("effectiveSpeedMps", Math.round(trainSpeed * 100.0) / 100.0);
                details.put("movementAuthorityEndMeters", Math.round(maEnd * 10.0) / 10.0);
                details.put("maDistanceMeters", Math.round(maDistance * 10.0) / 10.0);
                details.put("speedFactor", Math.round(constraint.speedFactor() * 1000.0) / 1000.0);
                details.put("speedLimitMps", Math.round(ma.speedLimitMetersPerSecond() * 100.0) / 100.0);
                details.put("currentSegmentId", ma.currentSegmentId());
                details.put("endSegmentId", ma.endSegmentId());
                details.put("constraintSource", constraintSource);
                details.put("constraintReason", constraint.reason());
                details.put("holdTrain", constraint.holdTrain());
                if (constraint.targetSpeedMetersPerSecond() != null) {
                    details.put("targetSpeedMps", Math.round(constraint.targetSpeedMetersPerSecond() * 100.0) / 100.0);
                }
                if (ma.reason() != null && !ma.reason().isBlank()) {
                    details.put("maReason", ma.reason());
                }
                if (ma.reasonCode() != null && !ma.reasonCode().isBlank()) {
                    details.put("reasonCode", ma.reasonCode());
                }
                // 限速来源分解
                Map<String, Object> limitBreakdown = buildSpeedLimitBreakdown(train, ma, constraint);
                if (!limitBreakdown.isEmpty()) {
                    details.put("limitBreakdown", limitBreakdown);
                }

                feedbacks.add(new DispatchCommandFeedback(
                    commandId, constraint.trainId(), null,
                    "SIGNAL_RUNTIME",
                    CommandStatus.APPLIED,
                    constraintSource != null
                        ? "dispatch constraint applied, limited by " + constraintSource
                        : "dispatch constraint applied",
                    Instant.now(),
                    details
                ));
            }
        }
        if (!feedbacks.isEmpty()) {
            dispatchService.acceptFeedback(feedbacks);
        }
    }

    private String deriveConstraintSource(MovementAuthority ma, DispatchConstraint constraint) {
        if (constraint.holdTrain()) {
            if (ma.reason().contains("站台停靠") || ma.reason().contains("dwell")) return "STATION_DWELL";
            if (ma.reason().contains("进路") || ma.reason().contains("route") || (ma.reasonCode() != null
                && ma.reasonCode().contains("ROUTE"))) return "ROUTE_CONFLICT";
            return "HOLD";
        }
        if (ma.reasonCode() != null) {
            if (ma.reasonCode().equals("MA_LIMITED")) return "MA_LIMITED";
            if (ma.reasonCode().equals("ROUTE_CONFLICT")) return "ROUTE_CONFLICT";
            if (ma.reasonCode().equals("STATION_DWELL")) return "STATION_DWELL";
        }
        if (ma.reason().contains("前车")) return "FRONT_TRAIN";
        if (ma.reason().contains("站台")) return "STATION_DWELL";
        if (ma.reason().contains("进路")) return "ROUTE_CONFLICT";
        if (ma.reason().contains("故障") || ma.reasonCode() != null && ma.reasonCode().contains("FAULT")) return "TRACK_FAULT";
        return "SIGNAL_MA";
    }

    private Map<String, Object> buildSpeedLimitBreakdown(TrainState train, MovementAuthority ma, DispatchConstraint constraint) {
        Map<String, Object> breakdown = new java.util.LinkedHashMap<>();
        breakdown.put("finalSpeedMps", Math.round(ma.speedLimitMetersPerSecond() * 100.0) / 100.0);
        breakdown.put("dispatchFactor", Math.round(constraint.speedFactor() * 100.0) / 100.0);
        if (constraint.targetSpeedMetersPerSecond() != null) {
            breakdown.put("dispatchTargetMps", Math.round(constraint.targetSpeedMetersPerSecond() * 100.0) / 100.0);
        }
        return breakdown;
    }

    /**
     * 处理调度发来的发车指令。
     *
     * <p>链路：调度时刻表触发 → DEPART指令 → 信号联锁检查 → 车辆层创建列车。
     * 发车分三步：
     * <ol>
     *   <li>解析 payload 中的列车参数（trainNo/linkId/offsetMeters/direction）</li>
     *   <li>联锁检查：进路是否可用（不实际建立，建路由后续 touchRoutes 自动完成）</li>
     *   <li>调车辆层创建列车 → 列车进场 → 下一 tick 的 touchRoutes 自动建进路 + MA</li>
     * </ol>
     *
     * <p>DEPART 命令的 payload 格式：
     * <pre>{@code
     *   {"trainNo":3, "linkId":1, "offsetMeters":0,
     *    "fromStation":"S01", "toStation":"S05", "direction":"DOWN"}
     * }</pre>
     */
    private void handleDepartures(List<DispatchCommand> departCommands) {
        if (departCommands == null || departCommands.isEmpty()) {
            return;
        }
        dispatchService.markCommandsSent(departCommands);
        List<DispatchCommandFeedback> feedbacks = new ArrayList<>();
        for (DispatchCommand cmd : departCommands) {
            Map<String, Object> payload = cmd.payload();
            if (payload == null) {
                log.warn("[Runtime] DEPART command {} has no payload, skipped", cmd.id());
                feedbacks.add(departureFeedback(cmd, CommandStatus.SKIPPED,
                    "DEPART_PAYLOAD_MISSING", "departure payload is missing", Map.of()));
                continue;
            }
            int trainNo = intFromPayload(payload, "trainNo", 0);
            if (trainNo <= 0) {
                log.warn("[Runtime] DEPART command {} missing trainNo", cmd.id());
                feedbacks.add(departureFeedback(cmd, CommandStatus.SKIPPED,
                    "DEPART_TRAIN_NO_INVALID", "trainNo must be positive", Map.of()));
                continue;
            }
            String trainId = cmd.trainId() == null || cmd.trainId().isBlank()
                ? "TR-%03d".formatted(trainNo)
                : cmd.trainId();
            int linkId = intFromPayload(payload, "linkId", 1);
            double offsetMeters = doubleFromPayload(payload, "offsetMeters", 0);
            String dirStr = stringFromPayload(payload, "direction", "DOWN");
            ExternalTrainDirection direction = "UP".equalsIgnoreCase(dirStr)
                ? ExternalTrainDirection.UP : ExternalTrainDirection.DOWN;

            // 安全门：如果列车已存在，跳过
            if (trainManager.states().stream().anyMatch(t -> t.id().equals(trainId))) {
                log.info("[Runtime] Train {} already exists, skip DEPART", trainId);
                feedbacks.add(departureFeedback(cmd, CommandStatus.EFFECT_CONFIRMED,
                    "TRAIN_ALREADY_ACTIVE", "train already active", Map.of("trainId", trainId)));
                continue;
            }

            // 联锁安全门：确认起止站间至少有一条进路可用
            // 若无信号机数据或进路表为空，跳过此检查（列车进场后 touchRoutes 自动建）
            String fromStation = stringFromPayload(payload, "fromStation", null);
            String toStation = stringFromPayload(payload, "toStation", null);

            boolean routeAccepted = true;
            String routeReason = null;
            if (fromStation != null && toStation != null
                && !interlockingService.states().isEmpty()) {
                String routeDetail = "{\"fromStation\":\"%s\",\"toStation\":\"%s\"}".formatted(fromStation, toStation);
                var result = interlockingService.applyDispatchCommand("REROUTE", routeDetail, trainId);
                if (!result.accepted()) {
                    routeAccepted = false;
                    routeReason = result.rejectReason();
                    log.warn("[Runtime] 联锁拒绝发车 {}: {}", trainId, result.rejectReason());
                    // 允许车辆上线接入，但由信号保持零速，直到进路真正建立。
                    interlockingService.holdTrainUntilRouteEstablished(trainId, result.rejectReason());
                }
            }

            // 创建列车 — 信号→车辆转译
            SignalTrainLifecycleTrainSpec spec = new SignalTrainLifecycleTrainSpec(
                trainNo, linkId, offsetMeters, direction
            );
            try {
                trainManager.applyLifecycleCommand(SignalTrainLifecycleCommand.add(List.of(spec)));
                // 下行列车从线路末端出发：显式绑定到下行段，避免 segmentAt 回退选到上行段
                double lineLen = infrastructureCatalog.lineData().lineLengthMeters();
                String track = ExternalTrainDirection.DOWN.equals(direction) ? "down" : "up";
                trackService.assignTrainToTrack(trainId, offsetMeters, track);
                log.info("[Runtime] 发车 {} — trainNo={} linkId={} offset={}m direction={}",
                    trainId, trainNo, linkId, offsetMeters, direction);
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("trainId", trainId);
                details.put("serviceId", stringFromPayload(payload, "serviceId", ""));
                details.put("circulationId", stringFromPayload(payload, "circulationId", ""));
                details.put("routeAccepted", routeAccepted);
                if (routeReason != null) {
                    details.put("routeRestrictionReason", routeReason);
                }
                feedbacks.add(departureFeedback(cmd, CommandStatus.EFFECT_CONFIRMED,
                    "TRAIN_CREATED", routeAccepted ? "train created" : "train created under route hold", details));
            } catch (Exception e) {
                interlockingService.clearRouteHold(trainId);
                log.error("[Runtime] 发车失败 {}: {}", trainId, e.getMessage());
                feedbacks.add(departureFeedback(cmd, CommandStatus.SKIPPED,
                    "TRAIN_CREATION_FAILED", e.getMessage(), Map.of("trainId", trainId)));
            }
        }
        dispatchService.acceptFeedback(feedbacks);
    }

    private DispatchCommandFeedback departureFeedback(
        DispatchCommand command,
        String status,
        String resultCode,
        String reason,
        Map<String, Object> details
    ) {
        Map<String, Object> structured = new java.util.LinkedHashMap<>(details);
        structured.put("resultCode", resultCode);
        structured.put("accepted", CommandStatus.EFFECT_CONFIRMED.equals(status));
        return new DispatchCommandFeedback(
            command.id(), command.trainId(), command.commandType(), "SIMULATION_RUNTIME",
            status, reason == null ? resultCode : reason, simulatedTime, structured
        );
    }


    private static int intFromPayload(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return fallback;
    }

    private static double doubleFromPayload(Map<String, Object> payload, String key, double fallback) {
        Object value = payload.get(key);
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {} }
        return fallback;
    }

    private static String stringFromPayload(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value != null ? value.toString() : fallback;
    }

    private void persistIfDue(TickContext context) {
        long persistenceStepMillis = simulationProperties.getPersistenceStepMillis();
        long tickMillis = simulationProperties.getTickMillis();
        if (persistenceStepMillis <= 0 || tickMillis <= 0) {
            return;
        }
        long elapsedMillis = context.tick() * tickMillis;
        if (elapsedMillis % persistenceStepMillis == 0) {
            simulationRunService.recordTickBestEffort(context.simulationRunId(), context.tick());
            persistenceService.persist(
                context,
                trainManager.states(),
                powerService.states(),
                lastEvents
            );
            persistenceService.persistTrackOccupancy(context, trackService.states());
            persistenceService.persistSignalStates(context, signalService.authorities());
        }
    }

    private SimulationSnapshot buildSnapshot() {
        simulationRunContext.update(dispatchService.simulationRunId(), tick);
        return monitorService.buildSnapshot(
            dispatchService.simulationRunId(),
            tick,
            simulatedTime,
            status,
            trainManager.states(),
            trackService.states(),
            signalService.authorities(),
            signalService.signalStates(),
            trackService.switchStates(),
            interlockingService.states(),
            powerService.states(),
            trainManager.vehicleRuntimeHealth(),
            powerService.externalSnapshot(),
            powerService.externalHealth(),
            lastEvents,
            dispatchService.snapshot()
        );
    }
}
