package com.railwaysim.signal;

/**
 * 联锁失败码枚举 — 信号层标准化的拒绝原因分类。
 * 调度模块使用这些码判断是否可以重试、是否需要换路。
 */
public enum InterlockingFailureCode {
    /** 成功建立进路 */
    ROUTE_ESTABLISHED(false, true),
    /** 进路定义不存在 */
    ROUTE_NOT_FOUND(false, false),
    /** 区段被其他列车占用 */
    TRACK_OCCUPIED(true, false),
    /** 区段处于故障状态 */
    TRACK_FAULT(true, false),
    /** 与已建立进路冲突（敌对进路） */
    ROUTE_CONFLICT(true, false),
    /** 道岔被锁闭无法扳动 */
    SWITCH_LOCKED(true, false),
    /** 道岔无法移动到目标位置 */
    SWITCH_MOVE_FAILED(true, false),
    /** 进路不在可用状态 */
    ROUTE_NOT_AVAILABLE(true, false),
    /** 道岔要求矛盾（需要同时NORMAL和REVERSE） */
    SWITCH_POSITION_MISMATCH(false, false),
    /** 区段在地图中不存在 */
    TRACK_NOT_FOUND(false, false),
    /** 不支持的命令类型 */
    UNSUPPORTED_COMMAND(false, false),
    /** 无匹配的进路 */
    NO_MATCHING_ROUTE(false, false),
    /** 内部错误 */
    INTERNAL_ERROR(false, false);

    private final boolean retryable;
    private final boolean success;

    InterlockingFailureCode(boolean retryable, boolean success) {
        this.retryable = retryable;
        this.success = success;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isSuccess() {
        return success;
    }

    /**
     * 根据拒绝原因字符串匹配对应的失败码。
     * 调度模块优先使用结构化码；此映射保证兼容旧版字符串格式。
     */
    public static InterlockingFailureCode fromRejectionReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ROUTE_ESTABLISHED;
        }
        String normalized = reason.toUpperCase();
        if (normalized.contains("DOES NOT EXIST") || normalized.contains("NOT FOUND")) {
            if (normalized.contains("TRACK")) return TRACK_NOT_FOUND;
            return ROUTE_NOT_FOUND;
        }
        if (normalized.contains("NO MATCHING ROUTE")) return NO_MATCHING_ROUTE;
        if (normalized.contains("TRACK_OCCUPIED") || normalized.contains("OCCUPIED BY ANOTHER")) return TRACK_OCCUPIED;
        if (normalized.contains("TRACK_FAULT") || normalized.contains("FAULT STATE")) return TRACK_FAULT;
        if (normalized.contains("CONFLICT")) return ROUTE_CONFLICT;
        if (normalized.contains("ROUTE") && (normalized.contains(" IS LOCKED")
            || normalized.contains(" IS OCCUPIED") || normalized.contains(" IS RELEASING")
            || normalized.contains(" IS VALIDATING"))) return ROUTE_NOT_AVAILABLE;
        if (normalized.contains("SWITCH") && normalized.contains("LOCKED")) return SWITCH_LOCKED;
        if (normalized.contains("SWITCH") && (normalized.contains("CANNOT MOVE") || normalized.contains("MOVE"))) return SWITCH_MOVE_FAILED;
        if (normalized.contains("BOTH NORMAL AND REVERSE") || normalized.contains("MISMATCH")) return SWITCH_POSITION_MISMATCH;
        if (normalized.contains("NOT AVAILABLE") || normalized.contains("IS NOT AVAILABLE")) return ROUTE_NOT_AVAILABLE;
        if (normalized.contains("UNSUPPORTED")) return UNSUPPORTED_COMMAND;
        return INTERNAL_ERROR;
    }
}
