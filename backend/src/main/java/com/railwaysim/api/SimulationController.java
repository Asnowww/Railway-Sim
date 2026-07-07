package com.railwaysim.api;

import com.railwaysim.simulation.SimulationRuntime;
import com.railwaysim.simulation.SimulationSnapshot;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
public class SimulationController {

    private final SimulationRuntime simulationRuntime;

    public SimulationController(SimulationRuntime simulationRuntime) {
        this.simulationRuntime = simulationRuntime;
    }

    @GetMapping("/snapshot")
    public SimulationSnapshot snapshot() {
        return simulationRuntime.snapshot();
    }

    @PostMapping("/start")
    public SimulationSnapshot start() {
        return simulationRuntime.start();
    }

    @PostMapping("/pause")
    public SimulationSnapshot pause() {
        return simulationRuntime.pause();
    }

    @PostMapping("/reset")
    public SimulationSnapshot reset() {
        return simulationRuntime.reset();
    }
}

