package com.railwaysim.simulation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Owns the single global simulation clock for every connected client. */
@Component
public class SimulationClockScheduler {

    private final SimulationRuntime simulationRuntime;

    public SimulationClockScheduler(SimulationRuntime simulationRuntime) {
        this.simulationRuntime = simulationRuntime;
    }

    @Scheduled(fixedDelayString = "${railway.simulation.tick-millis:200}")
    public void advanceClock() {
        simulationRuntime.advanceScheduledTick();
    }
}
