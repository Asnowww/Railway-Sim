package com.railwaysim.simulation;

import com.railwaysim.api.SimulationWebSocketHandler;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.dispatch.integration.DispatchCommandPublisher;
import com.railwaysim.monitor.MonitorService;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.simulation.event.DomainEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Instant;
import java.util.List;
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
    private List<DomainEvent> lastEvents = List.of();
    private long tick;
    private SimulationStatus status = SimulationStatus.STOPPED;
    private Instant simulatedTime = Instant.now();
    private long lastPushAtMillis;

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
        RouteInterlockingService interlockingService
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
    }

    public synchronized SimulationSnapshot snapshot() {
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot start() {
        status = SimulationStatus.RUNNING;
        return advanceOneTick();
    }

    public synchronized SimulationSnapshot tick() {
        if (status == SimulationStatus.STOPPED) {
            status = SimulationStatus.RUNNING;
        }
        return advanceOneTick();
    }

    public synchronized SimulationSnapshot pause() {
        status = SimulationStatus.PAUSED;
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot reset() {
        tick = 0;
        status = SimulationStatus.STOPPED;
        simulatedTime = Instant.now();
        lastPushAtMillis = 0;
        dispatchService.reset();
        trainManager.reset();
        trackService.reset();
        signalService.reset();
        interlockingService.reset();
        powerService.reset();
        realtimeStateCache.clear();
        eventBus.drain();
        lastEvents = List.of();
        return buildSnapshot();
    }

    private SimulationSnapshot advanceOneTick() {
        tick++;
        simulatedTime = simulatedTime.plusMillis(simulationProperties.getTickMillis());
        TickContext context = new TickContext(
            tick,
            simulationProperties.getTickMillis(),
            simulationProperties.getTickMillis() / 1000.0,
            simulatedTime
        );

        List<TrainState> beforeTrainStates = trainManager.states();
        trackService.updateOccupancy(beforeTrainStates);
        List<TrackConstraint> trackConstraints = trackService.constraintsForTrains(beforeTrainStates);
        signalService.calculateAuthorities(beforeTrainStates, trackConstraints, List.of());
        dispatchService.evaluate(context, beforeTrainStates, signalService.authorities());

        // 拦截 REROUTE 调度指令 → 交联锁处理（在约束计算前）
        List<DispatchCommand> rerouteCmds = dispatchService.drainCommandsOfType("REROUTE");
        for (DispatchCommand cmd : rerouteCmds) {
            var result = interlockingService.applyDispatchCommand(cmd.commandType(), commandDetail(cmd), cmd.trainId());
            if (!result.accepted()) {
                log.warn("[Runtime] 联锁拒绝调度指令 {}: {}", cmd.id(), result.rejectReason());
            }
        }

        List<DispatchCommand> generatedCommands = dispatchService.drainCommands();
        if (!generatedCommands.isEmpty()) {
            dispatchCommandPublisher.publish(generatedCommands);
        }

        List<DispatchConstraint> dispatchConstraints = dispatchService.constraintsForTrains(beforeTrainStates);
        signalService.calculateAuthorities(beforeTrainStates, trackConstraints, dispatchConstraints);
        List<PowerConstraint> powerConstraints = powerService.constraintsForTrains(beforeTrainStates);

        List<VehiclePhysicsOutput> outputs = trainManager.tickAll(
            context,
            signalService.authorities(),
            trackConstraints,
            dispatchConstraints,
            powerConstraints
        );
        powerService.updateFromVehicleOutputs(outputs);
        trackService.updateOccupancy(trainManager.states());
        signalService.recomputeSignalAspects(); // 基于最终区段占用刷新灯色
        lastEvents = eventBus.drain();
        persistIfDue(context);

        SimulationSnapshot snapshot = buildSnapshot();
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastPushAtMillis >= simulationProperties.getPushIntervalMillis()) {
            webSocketHandler.broadcast(snapshot);
            lastPushAtMillis = nowMillis;
        }
        return snapshot;
    }

    private String commandDetail(DispatchCommand command) {
        if (command.payload() != null) {
            Object detail = command.payload().get("detail");
            if (detail != null) {
                return detail.toString();
            }
        }
        return command.reason();
    }

    private void persistIfDue(TickContext context) {
        long persistenceStepMillis = simulationProperties.getPersistenceStepMillis();
        long tickMillis = simulationProperties.getTickMillis();
        if (persistenceStepMillis <= 0 || tickMillis <= 0) {
            return;
        }
        long elapsedMillis = context.tick() * tickMillis;
        if (elapsedMillis % persistenceStepMillis == 0) {
            persistenceService.persist(
                context,
                trainManager.states(),
                powerService.states(),
                lastEvents
            );
        }
    }

    private SimulationSnapshot buildSnapshot() {
        return monitorService.buildSnapshot(
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
            lastEvents,
            dispatchService.snapshot()
        );
    }
}
