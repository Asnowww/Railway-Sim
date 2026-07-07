package com.railwaysim.simulation;

import com.railwaysim.api.SimulationWebSocketHandler;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.monitor.MonitorService;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SimulationRuntime {

    private final TrainManager trainManager;
    private final TrackService trackService;
    private final SignalService signalService;
    private final PowerService powerService;
    private final MonitorService monitorService;
    private final DispatchService dispatchService;
    private final SimulationWebSocketHandler webSocketHandler;
    private final SimulationProperties simulationProperties;

    private long tick;
    private SimulationStatus status = SimulationStatus.STOPPED;
    private Instant simulatedTime = Instant.now();
    private long lastPushAtMillis;

    public SimulationRuntime(
        TrainManager trainManager,
        TrackService trackService,
        SignalService signalService,
        PowerService powerService,
        MonitorService monitorService,
        DispatchService dispatchService,
        SimulationWebSocketHandler webSocketHandler,
        SimulationProperties simulationProperties
    ) {
        this.trainManager = trainManager;
        this.trackService = trackService;
        this.signalService = signalService;
        this.powerService = powerService;
        this.monitorService = monitorService;
        this.dispatchService = dispatchService;
        this.webSocketHandler = webSocketHandler;
        this.simulationProperties = simulationProperties;
    }

    public synchronized SimulationSnapshot snapshot() {
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot start() {
        status = SimulationStatus.RUNNING;
        simulatedTime = Instant.now();
        tick++;
        SimulationSnapshot snapshot = buildSnapshot();
        broadcastIfDue(snapshot, true);
        return snapshot;
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
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot tick() {
        if (status != SimulationStatus.RUNNING) {
            return snapshot();
        }

        tick++;
        long tickMillis = simulationProperties.getTickMillis();
        simulatedTime = simulatedTime.plusMillis(tickMillis);
        TickContext context = new TickContext(tick, tickMillis, tickMillis / 1000.0, simulatedTime);

        List<DispatchCommand> commands = dispatchService.drainCommands();
        CurrentRunPlan plan = dispatchService.currentPlan();
        trainManager.updatePlan(plan);
        trainManager.tickAll(context, commands);
        dispatchService.markCommandsApplied(commands);

        trackService.updateOccupancy(trainManager.states());
        powerService.update();
        dispatchService.evaluate(context, trainManager.states(), signalService.authorities());

        SimulationSnapshot snapshot = buildSnapshot();
        broadcastIfDue(snapshot, false);
        return snapshot;
    }

    private SimulationSnapshot buildSnapshot() {
        return monitorService.buildSnapshot(
            tick,
            simulatedTime,
            status,
            trainManager.states(),
            trackService.states(),
            signalService.authorities(),
            powerService.states(),
            dispatchService.snapshot()
        );
    }

    private void broadcastIfDue(SimulationSnapshot snapshot, boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastPushAtMillis >= simulationProperties.getPushIntervalMillis()) {
            webSocketHandler.broadcast(snapshot);
            lastPushAtMillis = now;
        }
    }
}
