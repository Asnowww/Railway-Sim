package com.railwaysim.dispatch.operation;

public record CirculationPlanRequest(
    Integer cycleTarget,
    Integer headwaySeconds,
    Integer leadSeconds
) {
}
