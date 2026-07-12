package com.railwaysim.simulation;

import org.springframework.stereotype.Component;

/** In-process projection of the dispatch-owned run id and current tick. */
@Component
public class SimulationRunContext {
    private volatile String runId = "";
    private volatile long tick;

    public void update(String runId, long tick) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        this.runId = runId;
        this.tick = tick;
    }

    public String runId() { return runId; }
    public long tick() { return tick; }
}
