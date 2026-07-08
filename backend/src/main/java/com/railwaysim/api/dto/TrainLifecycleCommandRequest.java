package com.railwaysim.api.dto;

import java.util.List;

public record TrainLifecycleCommandRequest(
    String action,
    List<TrainLifecycleTrainRequest> trains,
    String reason,
    String operator,
    String confirmToken,
    String traceId
) {
    public List<TrainLifecycleTrainRequest> normalizedTrains() {
        return trains == null ? List.of() : List.copyOf(trains);
    }

    public String normalizedOperator() {
        return operator == null || operator.isBlank() ? "simulation" : operator;
    }

    public String normalizedReason() {
        return reason == null || reason.isBlank() ? "external train lifecycle" : reason;
    }

    public String normalizedTraceId() {
        return traceId == null || traceId.isBlank() ? "trace-train-lifecycle" : traceId;
    }

    public record TrainLifecycleTrainRequest(
        Integer trainNo,
        Integer linkId,
        Double offsetMeters,
        String direction
    ) {
    }
}
