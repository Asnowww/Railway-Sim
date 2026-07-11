package com.railwaysim.signal;

/**
 * 进路状态枚举（WP-03 细化 — 原 3 态扩展为 8 态）。
 *
 * <pre>
 *   AVAILABLE     — 空闲，可接收请求
 *   VALIDATING    — 请求已接收，正在校验区段/道岔/位置
 *   SETTING_SWITCHES — 校验通过，正在扳动并复核道岔
 *   LOCKED        — 道岔已锁死、信号已开放、等待列车进入
 *   OCCUPIED      — 列车已进入进路
 *   RELEASING     — 列车部分出清，正在逐段释放
 *   RELEASED      — 完整出清，道岔解锁
 *   CONFLICTED    — 与另一已建立进路冲突
 *   REJECTED      — 校验/扳动失败
 *   FAILED        — 执行过程中发生不可恢复错误
 *   CANCELLED     — 调度主动取消（列车未进入）
 *   EXPIRED_BY_RESET — 仿真重置时强制终止
 * </pre>
 */
public enum RouteStatus {
    AVAILABLE,
    VALIDATING,
    SETTING_SWITCHES,
    LOCKED,
    OCCUPIED,
    RELEASING,
    RELEASED,
    CONFLICTED,
    REJECTED,
    FAILED,
    CANCELLED,
    EXPIRED_BY_RESET
}
