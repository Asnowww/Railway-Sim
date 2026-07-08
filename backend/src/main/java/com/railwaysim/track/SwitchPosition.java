package com.railwaysim.track;

/**
 * 道岔位置：定位(NORMAL) 或 反位(REVERSE)。
 *
 * <p>对应老师数据中道岔表的方向信息：
 * <ul>
 *   <li>NORMAL  — 道岔开通定位方向（直向）</li>
 *   <li>REVERSE — 道岔开通反位方向（侧向）</li>
 * </ul>
 */
public enum SwitchPosition {
    NORMAL,
    REVERSE
}
