package com.railwaysim.dispatch.optimization;

import java.util.Map;

public record LineRegulationDecision(
    String trainId,
    String regulatedTrainId,
    String frontTrainId,
    String action,
    String commandType,
    String status,
    String reason,
    Double currentHeadwaySec,
    int targetHeadwaySec,
    Double currentHeadwayErrorSec,
    Double predictedHeadwayErrorSec,
    double priorityScore,
    String signalConstraint,
    String commandId,
    Map<String, Object> payload
) {
    public LineRegulationDecision {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
