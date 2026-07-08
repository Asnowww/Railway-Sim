package com.railwaysim.signal;

import java.util.Set;

/**
 * 进路运行时状态。
 *
 * @param routeId            进路唯一标识
 * @param status             当前状态
 * @param lockedSwitchIds    联锁锁死的道岔 ID 集合（信号开放时锁定）
 * @param establishedByTrainId 建立该进路的列车 ID（null 表示空闲）
 * @param axleSegmentIds     核心区段列表（冲突检测用）
 */
public record RouteState(
    String routeId,
    RouteStatus status,
    Set<String> lockedSwitchIds,
    String establishedByTrainId,
    Set<String> axleSegmentIds
) {
    public RouteState withEstablished(String trainId) {
        return new RouteState(routeId, RouteStatus.ESTABLISHED, lockedSwitchIds, trainId, axleSegmentIds);
    }

    public RouteState withReleased() {
        return new RouteState(routeId, RouteStatus.AVAILABLE, Set.of(), null, axleSegmentIds);
    }

    public RouteState withConflicted() {
        return new RouteState(routeId, RouteStatus.CONFLICTED, Set.of(), null, axleSegmentIds);
    }

    /** 两进路是否冲突（共享核心区段） */
    public boolean conflictsWith(RouteState other) {
        if (other == null) return false;
        for (String id : axleSegmentIds) {
            if (other.axleSegmentIds.contains(id)) return true;
        }
        return false;
    }
}
