package com.railwaysim.api;

import com.railwaysim.signal.SignalService;
import com.railwaysim.simulation.SimulationRuntime;
import com.railwaysim.simulation.SimulationSnapshot;
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

    public SimulationController(
        SimulationRuntime simulationRuntime,
        TrackService trackService,
        SignalService signalService,
        TrainManager trainManager
    ) {
        this.simulationRuntime = simulationRuntime;
        this.trackService = trackService;
        this.signalService = signalService;
        this.trainManager = trainManager;
    }

    @GetMapping("/snapshot")
    public SimulationSnapshot snapshot() {
        return simulationRuntime.snapshot();
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

    @PostMapping("/reset")
    public SimulationSnapshot reset() {
        return simulationRuntime.reset();
    }


    // ---- 故障注入（信号轨道模块）——

    @PostMapping("/fault/inject")
    public SimulationSnapshot injectFault(@RequestParam String segmentId) {
        trackService.injectFault(segmentId);
        signalService.calculateAuthorities(
            trainManager.states(),
            trackService.constraintsForTrains(trainManager.states())
        );
        return simulationRuntime.snapshot();
    }

    @PostMapping("/fault/clear")
    public SimulationSnapshot clearFault(@RequestParam String segmentId) {
        trackService.clearFault(segmentId);
        signalService.calculateAuthorities(
            trainManager.states(),
            trackService.constraintsForTrains(trainManager.states())
        );
        return simulationRuntime.snapshot();
    }
}
