package com.railwaysim.signal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
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
    public RouteState {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(status, "status");
        lockedSwitchIds = immutableOrderedSet(lockedSwitchIds);
        axleSegmentIds = immutableOrderedSet(axleSegmentIds);
        if (status.holdsInterlockingResources()
            && (establishedByTrainId == null || establishedByTrainId.isBlank())) {
            throw new IllegalArgumentException(status + " route requires an established train");
        }
    }

    public RouteState transitionTo(RouteStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal route transition " + status + " -> " + target);
        }
        if (target == RouteStatus.LOCKED) {
            throw new IllegalStateException("Use withLocked to supply route ownership");
        }
        boolean retainsResources = target == RouteStatus.OCCUPIED || target == RouteStatus.RELEASING;
        return new RouteState(
            routeId,
            target,
            retainsResources ? lockedSwitchIds : Set.of(),
            retainsResources ? establishedByTrainId : null,
            axleSegmentIds
        );
    }

    public RouteState withLocked(Set<String> switchIds, String trainId) {
        if (!status.canTransitionTo(RouteStatus.LOCKED)) {
            throw new IllegalStateException("Illegal route transition " + status + " -> LOCKED");
        }
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required when locking a route");
        }
        return new RouteState(routeId, RouteStatus.LOCKED, switchIds, trainId, axleSegmentIds);
    }

    private static Set<String> immutableOrderedSet(Set<String> values) {
        return values == null || values.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
