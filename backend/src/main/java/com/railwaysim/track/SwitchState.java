package com.railwaysim.track;

/**
 * 道岔运行时状态。每个仿真步长由 TrackService 维护。
 *
 * <p>联锁规则（来自详细设计 3.3 节）：
 * <ol>
 *   <li>道岔位置不对 → 信号不能开放</li>
 *   <li>信号开放 → 道岔锁死，禁止扳动</li>
 *   <li>冲突进路 → 只能建立一条</li>
 * </ol>
 *
 * @param id             道岔唯一标识（如 DC1, DC2）
 * @param nodeId         道岔所在拓扑节点 ID
 * @param position       当前开通方向 NORMAL / REVERSE
 * @param locked         是否被联锁锁闭（锁闭时禁止扳动）
 * @param activeSegmentId 当前激活的区段边 ID
 */
public record SwitchState(
    String id,
    String nodeId,
    SwitchPosition position,
    boolean locked,
    String activeSegmentId
) {
    public SwitchState withPosition(SwitchPosition nextPosition) {
        return new SwitchState(id, nodeId, nextPosition, locked, activeSegmentId);
    }

    public SwitchState withLocked(boolean nextLocked) {
        return new SwitchState(id, nodeId, position, nextLocked, activeSegmentId);
    }

    public SwitchState withActiveSegment(String nextSegmentId) {
        return new SwitchState(id, nodeId, position, locked, nextSegmentId);
    }
}
