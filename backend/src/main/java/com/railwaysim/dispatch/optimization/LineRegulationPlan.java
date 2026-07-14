package com.railwaysim.dispatch.optimization;

import java.time.Instant;
import java.util.List;

public record LineRegulationPlan(
    String planId,
    Instant generatedAt,
    String objective,
    String status,
    int targetHeadwaySec,
    Double currentMaxAbsHeadwayErrorSec,
    Double predictedMaxAbsHeadwayErrorSec,
    int commandCount,
    List<LineRegulationDecision> decisions
) {
    public LineRegulationPlan {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public static LineRegulationPlan empty(String planId, Instant generatedAt, int targetHeadwaySec) {
        return new LineRegulationPlan(
            planId,
            generatedAt,
            "RESTORE_EVEN_HEADWAY",
            "NO_DATA",
            targetHeadwaySec,
            null,
            null,
            0,
            List.of()
        );
    }
}
