package com.railwaysim.dispatch.optimization;

import com.railwaysim.dispatch.DispatchCommand;
import java.util.List;

public record LineHeadwayOptimizationResult(
    LineRegulationPlan plan,
    List<DispatchCommand> commands
) {
    public LineHeadwayOptimizationResult {
        commands = commands == null ? List.of() : List.copyOf(commands);
    }
}
