package com.railwaysim.dispatch.operation;

import java.time.Instant;
import java.util.List;

public record TrainCirculationPlan(
    String circulationId,
    String simulationRunId,
    String templateId,
    String trainId,
    String startTerminalId,
    int cycleTarget,
    int cycleCompleted,
    int currentLegPointer,
    String status,
    int headwaySeconds,
    Instant plannedStartAt,
    Instant createdAt,
    Instant updatedAt,
    List<CirculationLeg> legs
) {
    public TrainCirculationPlan {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public CirculationLeg currentLeg() {
        if (currentLegPointer < 0 || currentLegPointer >= legs.size()) {
            return null;
        }
        return legs.get(currentLegPointer);
    }

    public TrainCirculationPlan withLeg(int index, CirculationLeg leg, String status, Instant updatedAt) {
        java.util.ArrayList<CirculationLeg> copy = new java.util.ArrayList<>(legs);
        copy.set(index, leg);
        return new TrainCirculationPlan(
            circulationId, simulationRunId, templateId, trainId, startTerminalId,
            cycleTarget, cycleCompleted, currentLegPointer, status, headwaySeconds,
            plannedStartAt, createdAt, updatedAt, copy
        );
    }

    public TrainCirculationPlan advanceAfterCompletedLeg(Instant updatedAt) {
        int nextPointer = currentLegPointer + 1;
        int nextCompleted = cycleCompleted;
        String nextStatus = CirculationPlanStatus.IN_SERVICE;
        if (nextPointer >= legs.size()) {
            nextPointer = legs.size();
            nextCompleted = cycleTarget;
            nextStatus = CirculationPlanStatus.RESTING;
        }
        return new TrainCirculationPlan(
            circulationId, simulationRunId, templateId, trainId, startTerminalId,
            cycleTarget, nextCompleted, nextPointer, nextStatus, headwaySeconds,
            plannedStartAt, createdAt, updatedAt, legs
        );
    }

    public TrainCirculationPlan cancelled(Instant updatedAt) {
        return new TrainCirculationPlan(
            circulationId, simulationRunId, templateId, trainId, startTerminalId,
            cycleTarget, cycleCompleted, currentLegPointer, CirculationPlanStatus.CANCELLED,
            headwaySeconds, plannedStartAt, createdAt, updatedAt, legs
        );
    }
}
