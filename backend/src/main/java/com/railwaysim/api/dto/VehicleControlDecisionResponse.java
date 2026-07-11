package com.railwaysim.api.dto;

import com.railwaysim.vehicle.control.VehicleControlDecision;
import com.railwaysim.vehicle.control.VehicleOperationMode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 控制决策查询 API 响应。
 */
public record VehicleControlDecisionResponse(
    String decisionId,
    String runId,
    long tick,
    String trainId,
    String mode,
    String source,
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrake,
    double direction,
    boolean doorClosed,
    boolean currentCollectionAvailable,
    boolean tractionAvailable,
    boolean brakeAvailable,
    List<String> overriddenCandidateIds,
    String selectedReasonCode,
    Instant inputTimestamp,
    Instant decidedAt,
    String traceId,
    int decisionVersion
) {
    public static VehicleControlDecisionResponse from(VehicleControlDecision d) {
        return new VehicleControlDecisionResponse(
            d.decisionId(), d.runId(), d.tick(), d.trainId(),
            d.mode().name(), d.source(),
            d.tractionCommand(), d.brakeCommand(), d.emergencyBrake(),
            d.direction(), d.doorClosed(), d.currentCollectionAvailable(),
            d.tractionAvailable(), d.brakeAvailable(),
            d.overriddenCandidateIds(), d.selectedReasonCode(),
            d.inputTimestamp(), d.decidedAt(), d.traceId(),
            d.decisionVersion()
        );
    }
}
