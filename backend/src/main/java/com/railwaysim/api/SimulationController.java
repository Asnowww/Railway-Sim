package com.railwaysim.api;

import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.simulation.SimulationRuntime;
import com.railwaysim.simulation.SimulationSnapshot;
import com.railwaysim.simulation.SimulationStatus;
import com.railwaysim.simulation.SimulationTickTiming;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
public class SimulationController {

    private final SimulationRuntime simulationRuntime;
    private final TrackService trackService;
    private final SignalService signalService;
    private final TrainManager trainManager;
    private final DispatchService dispatchService;

    public SimulationController(
        SimulationRuntime simulationRuntime,
        TrackService trackService,
        SignalService signalService,
        TrainManager trainManager,
        DispatchService dispatchService
    ) {
        this.simulationRuntime = simulationRuntime;
        this.trackService = trackService;
        this.signalService = signalService;
        this.trainManager = trainManager;
        this.dispatchService = dispatchService;
    }

    @GetMapping("/snapshot")
    public SimulationSnapshot snapshot() {
        return simulationRuntime.snapshot();
    }

    @GetMapping("/timing")
    public SimulationTickTiming timing() {
        return simulationRuntime.latestTickTiming();
    }

    @PostMapping("/start")
    public SimulationSnapshot start() {
        return simulationRuntime.start();
    }

    @PostMapping("/tick")
    public SimulationSnapshot tick() {
        return simulationRuntime.tick();
    }

    @PostMapping("/pause")
    public SimulationSnapshot pause() {
        return simulationRuntime.pause();
    }

    @PostMapping("/stop")
    public SimulationSnapshot stop() {
        return simulationRuntime.stop();
    }

    @PostMapping("/reset")
    public SimulationSnapshot reset() {
        return simulationRuntime.reset();
    }


    // ---- 故障注入（信号轨道模块）——

    @PostMapping("/fault/inject")
    public SimulationSnapshot injectFault(@RequestParam String segmentId) {
        SimulationSnapshot current = simulationRuntime.snapshot();
        if (current.status() == SimulationStatus.RUNNING) {
            throw new IllegalStateException("Cannot inject fault while simulation is RUNNING — pause first");
        }
        synchronized (simulationRuntime) {
            trackService.injectFault(segmentId);
            trackService.updateOccupancy(trainManager.states());
            signalService.calculateAuthorities(
                trainManager.states(),
                trackService.constraintsForTrains(trainManager.states()),
                dispatchService.previewConstraintsForTrains(trainManager.states())
            );
            return simulationRuntime.snapshot();
        }
    }

    @PostMapping("/fault/clear")
    public SimulationSnapshot clearFault(@RequestParam String segmentId) {
        SimulationSnapshot current = simulationRuntime.snapshot();
        if (current.status() == SimulationStatus.RUNNING) {
            throw new IllegalStateException("Cannot clear fault while simulation is RUNNING — pause first");
        }
        synchronized (simulationRuntime) {
            trackService.clearFault(segmentId);
            trackService.updateOccupancy(trainManager.states());
            signalService.calculateAuthorities(
                trainManager.states(),
                trackService.constraintsForTrains(trainManager.states()),
                dispatchService.previewConstraintsForTrains(trainManager.states())
            );
            return simulationRuntime.snapshot();
        }
    }
}
