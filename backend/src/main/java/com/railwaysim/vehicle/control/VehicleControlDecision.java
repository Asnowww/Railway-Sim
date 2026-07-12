package com.railwaysim.vehicle.control;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 每 tick 每车生成的不可变最终控制决策。
 * <p>
 * 包含完整溯源信息，支持审计和回放。
 */
/** @deprecated LOCAL mode only. Retained for LOCAL fallback persistence. */
@Deprecated(forRemoval=true, since="2.0")
public record VehicleControlDecision(
    String decisionId,
    String runId,
    long tick,
    String trainId,
    VehicleOperationMode mode,
    String source,                      // 被采用候选的来源
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
    public VehicleControlDecision {
        decisionId = decisionId != null ? decisionId : UUID.randomUUID().toString();
        decidedAt = decidedAt != null ? decidedAt : Instant.now();
        overriddenCandidateIds = overriddenCandidateIds != null
            ? List.copyOf(overriddenCandidateIds)
            : List.of();
    }

    /**
     * 是否施加了紧急制动。
     */
    public boolean isEmergencyBrakeActive() {
        return emergencyBrake;
    }

    /**
     * 是否允许牵引（紧急制动、供电约束、门未关等安全层禁止牵引）。
     */
    public boolean isTractionPermitted() {
        return !emergencyBrake && tractionAvailable && brakeAvailable
            && currentCollectionAvailable && doorClosed;
    }

    /**
     * 是否为安全层强制产生的决策（非人工/自动命令）。
     */
    public boolean isSafetyEnforced() {
        return "EMERGENCY_BRAKE".equals(selectedReasonCode)
            || "SAFETY_BRAKE".equals(selectedReasonCode)
            || "POWER_LOSS".equals(selectedReasonCode)
            || "DOOR_KEY_BLOCK".equals(selectedReasonCode)
            || "MA_ATP_LIMIT".equals(selectedReasonCode);
    }

    /**
     * 构建一个默认的安全制动决策（用于紧急情况）。
     */
    public static VehicleControlDecision emergencyBrakeDecision(
        String runId, long tick, String trainId, String traceId
    ) {
        return new VehicleControlDecision(
            null, runId, tick, trainId, VehicleOperationMode.MANUAL,
            "SAFETY_LAYER", 0.0, 1.0, true, 0.0,
            true, true, true, true,
            List.of(), "EMERGENCY_BRAKE",
            Instant.now(), Instant.now(), traceId, 1
        );
    }
}
