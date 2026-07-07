package com.railwaysim.simulation;

import com.railwaysim.api.SimulationWebSocketHandler;
import com.railwaysim.monitor.MonitorService;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class SimulationRuntime {

    private final TrainManager trainManager;
    private final TrackService trackService;
    private final SignalService signalService;
    private final PowerService powerService;
    private final MonitorService monitorService;
    private final SimulationWebSocketHandler webSocketHandler;
    private long tick;
    private SimulationStatus status = SimulationStatus.STOPPED;

    public SimulationRuntime(
        TrainManager trainManager,
        TrackService trackService,
        SignalService signalService,
        PowerService powerService,
        MonitorService monitorService,
        SimulationWebSocketHandler webSocketHandler
    ) {
        this.trainManager = trainManager;
        this.trackService = trackService;
        this.signalService = signalService;
        this.powerService = powerService;
        this.monitorService = monitorService;
        this.webSocketHandler = webSocketHandler;
    }

    public synchronized SimulationSnapshot snapshot() {
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot start() {
        status = SimulationStatus.RUNNING;
        tick++;
        SimulationSnapshot snapshot = buildSnapshot();
        webSocketHandler.broadcast(snapshot);
        return snapshot;
    }

    public synchronized SimulationSnapshot pause() {
        status = SimulationStatus.PAUSED;
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot reset() {
        tick = 0;
        status = SimulationStatus.STOPPED;
        trainManager.reset();
        return buildSnapshot();
    }

    private SimulationSnapshot buildSnapshot() {
        return monitorService.buildSnapshot(
            tick,
            Instant.now(),
            status,
            trainManager.states(),
            trackService.states(),
            signalService.authorities(),
            powerService.states()
        );
    }
}

