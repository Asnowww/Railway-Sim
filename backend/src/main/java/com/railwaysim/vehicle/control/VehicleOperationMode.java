package com.railwaysim.vehicle.control;

/**
 * 车辆控制模式 — 命令来源等级，不改变安全约束优先级。
 * <p>
 * 参照 IEC 62290 自动化等级概念：人工/自动/智能只表达"谁在发命令"，
 * 紧急制动、MA、门/钥匙/自检、制动可用性、供电约束作为始终生效的独立安全层。
 */
/** @deprecated LOCAL mode only. 9300's VehicleRuntimeInstance.resolveOperationMode() is authoritative in EXTERNAL_HTTP mode. */
@Deprecated(forRemoval=true, since="2.0")
public enum VehicleOperationMode {
    /**
     * 人工驾驶：司机台命令为最高非安全候选来源。
     */
    MANUAL,

    /**
     * 自动运行：ATO/自动列车运行命令为最高非安全候选来源。
     */
    AUTO,

    /**
     * 智能/增强模式：可供高级自动驾驶算法使用，优先级同 AUTO。
     */
    INTELLIGENT
}
