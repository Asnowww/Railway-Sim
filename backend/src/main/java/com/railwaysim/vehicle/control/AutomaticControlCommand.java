package com.railwaysim.vehicle.control;

/**
 * 自动/ATO 控制命令 — 不可变值对象。
 * <p>
 * 当前自动命令由规则推断（状态机 `decideDynamicsState`），
 * 此处预留结构以便未来注入 ATO 系统的显式命令。
 */
/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public record AutomaticControlCommand(
    String commandId,
    String trainId,
    String source,                  // "ATO", "SCHEDULE", "RULE_ENGINE"
    double tractionCommand,         // 0.0 ~ 1.0
    double brakeCommand,            // 0.0 ~ 1.0
    boolean emergencyBrake,
    double direction,
    String traceId
) {
    public boolean isTractionCommandValid() {
        return tractionCommand >= 0.0 && tractionCommand <= 1.0;
    }

    public boolean isBrakeCommandValid() {
        return brakeCommand >= 0.0 && brakeCommand <= 1.0;
    }
}
