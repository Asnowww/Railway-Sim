package com.railwaysim.signal;

/**
 * 信号机灯色状态机：红/黄/绿三色。
 *
 * <pre>
 *   RED    — 前方区段被占用或有故障，禁止通行
 *   YELLOW — 前方区段已被预留(RESERVED)，注意减速准备停车
 *   GREEN  — 前方区段空闲(FREE)，可按授权速度通行
 * </pre>
 */
public enum SignalAspect {
    RED,
    YELLOW,
    GREEN
}
