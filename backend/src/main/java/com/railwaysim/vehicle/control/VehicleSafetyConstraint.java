package com.railwaysim.vehicle.control;

/**
 * 车辆安全约束 — 独立于操作模式的始终生效的安全层。
 * <p>
 * 优先级（数字越小优先级越高）：
 * <ol>
 *   <li>EMERGENCY_BRAKE — 紧急制动</li>
 *   <li>MA_ATP — 行车许可 / ATP 限速</li>
 *   <li>DOOR_KEY_SELFCHECK — 门/钥匙/自检/制动可用性</li>
 *   <li>POWER_CONSTRAINT — 供电约束（失电/欠压/降额）</li>
 * </ol>
 */
/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public enum VehicleSafetyConstraint {
    EMERGENCY_BRAKE(0),
    MA_ATP(1),
    COMMAND_STALE(2),
    DOOR_KEY_SELFCHECK(3),
    POWER_CONSTRAINT(4);

    private final int priority;

    VehicleSafetyConstraint(int priority) {
        this.priority = priority;
    }

    /**
     * 安全约束的优先级序号。0 = 最高优先级。
     */
    public int priority() {
        return priority;
    }

    /**
     * 判断此约束的优先级是否高于另一约束。
     */
    public boolean outranks(VehicleSafetyConstraint other) {
        return this.priority < other.priority;
    }
}
