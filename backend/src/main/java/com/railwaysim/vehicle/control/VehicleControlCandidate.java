package com.railwaysim.vehicle.control;

import java.time.Instant;

/**
 * 控制候选 — 参与仲裁的单个候选命令，附带来源和优先级元数据。
 * <p>
 * 仲裁器收集所有候选，按优先级选择生效的一个。
 */
/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public record VehicleControlCandidate(
    String candidateId,
    String trainId,
    String source,                      // "DRIVER", "ATO", "SCHEDULE", "SAFETY_LAYER"
    VehicleSafetyConstraint safetyConstraint,
    VehicleOperationMode operationMode,
    double tractionCommand,
    double brakeCommand,
    boolean emergencyBrake,
    double direction,
    boolean doorOpenRequest,
    Instant createdAt
) {
    /**
     * 此候选是否由安全约束（而非人工/自动命令）产生。
     */
    public boolean isSafetyDerived() {
        return safetyConstraint != null;
    }

    /**
     * 是否包含有效的牵引命令。
     */
    public boolean requiresTraction() {
        return tractionCommand > 0.001 && !emergencyBrake;
    }

    /**
     * 是否要求制动。
     */
    public boolean requiresBrake() {
        return brakeCommand > 0.001 || emergencyBrake;
    }
}
