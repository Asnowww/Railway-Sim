package com.railwaysim.monitor;

import java.util.List;

public record FaultImpact(
    String severity,
    List<String> affectedTrainIds,
    List<String> affectedSectionIds,
    String safetyAction,
    String clearCondition,
    String recoveryCondition
) {
    public FaultImpact {
        affectedTrainIds = affectedTrainIds == null ? List.of() : List.copyOf(affectedTrainIds);
        affectedSectionIds = affectedSectionIds == null ? List.of() : List.copyOf(affectedSectionIds);
        severity = severity == null || severity.isBlank() ? "WARNING" : severity;
        safetyAction = safetyAction == null ? "MONITOR" : safetyAction;
        clearCondition = clearCondition == null ? "SOURCE_CONDITION_CLEARED" : clearCondition;
        recoveryCondition = recoveryCondition == null ? "STATE_RECONCILED" : recoveryCondition;
    }
}
