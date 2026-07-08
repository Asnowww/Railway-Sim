package com.railwaysim.signal;

/**
 * 进路状态枚举。
 *
 * <pre>
 *   AVAILABLE  — 进路空闲，可建立
 *   ESTABLISHED — 进路已建立（信号开放、道岔锁死）
 *   CONFLICTED  — 被其他已建立的进路冲突，不可建立
 * </pre>
 */
public enum RouteStatus {
    AVAILABLE,
    ESTABLISHED,
    CONFLICTED
}
