package com.railwaysim.signal;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 进路联锁服务 —— 实现三条核心联锁规则。
 *
 * <h3>规则（来自详细设计 3.3 节）</h3>
 * <ol>
 *   <li>道岔位置不对 → 信号不能开放</li>
 *   <li>信号开放 → 道岔锁死禁止扳动</li>
 *   <li>冲突进路 → 只能建立一条</li>
 * </ol>
 *
 * <h3>进路生命周期</h3>
 * <pre>
 *   AVAILABLE ──establish──→ ESTABLISHED ──release──→ AVAILABLE
 *       ↑                      ↓
 *       └──── 后方进路冲突 ─── CONFLICTED
 * </pre>
 *
 * <h3>内部分层</h3>
 * 联锁只管"安全能不能放行"，不管"策略要不要放行"。
 * 前者归联锁，后者归调度/信号上层——对应和刘倩的分工边界。
 *
 * @author 黄旭涛 (信号 + 轨道仿真)
 */
@Service
public class RouteInterlockingService {

    private static final Logger log = LoggerFactory.getLogger(RouteInterlockingService.class);

    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final TrackService trackService;
    private final Map<String, RouteState> routeStates = new HashMap<>();

    public RouteInterlockingService(
        StaticInfrastructureCatalog infrastructureCatalog,
        TrackService trackService
    ) {
        this.infrastructureCatalog = infrastructureCatalog;
        this.trackService = trackService;
    }

    // ==================== 生命周期 ====================

    public synchronized void reset() {
        routeStates.clear();
        OperationalLineData lineData = infrastructureCatalog.lineData();
        if (lineData.routes() != null) {
            for (OperationalLineData.RouteDefinition routeDef : lineData.routes()) {
                routeStates.put(routeDef.id(), new RouteState(
                    routeDef.id(),
                    RouteStatus.AVAILABLE,
                    Set.of(),
                    null,
                    new HashSet<>(routeDef.axleSectionIds() == null ? List.of() : routeDef.axleSectionIds())
                ));
            }
        }
    }

    // ==================== 联锁核心 ====================

    /**
     * 尝试为指定列车建立进路。
     *
     * <p>按联锁三条规则依次检查，通过后锁死道岔并开放信号。
     *
     * @param routeId 进路 ID
     * @param trainId 请求建立进路的列车
     * @return 检查通过返回 null，否则返回拒绝原因
     */
    public synchronized String establishRoute(String routeId, String trainId) {
        RouteState route = routeStates.get(routeId);
        if (route == null) {
            return "进路 " + routeId + " 不存在";
        }
        if (route.status() == RouteStatus.ESTABLISHED) {
            return "进路 " + routeId + " 已被 " + route.establishedByTrainId() + " 占用";
        }

        // ---- 规则3：冲突进路检测 ----
        for (RouteState existing : routeStates.values()) {
            if (existing.status() == RouteStatus.ESTABLISHED && route.conflictsWith(existing)) {
                routeStates.put(routeId, route.withConflicted());
                return "进路 " + routeId + " 与已建立的进路 " + existing.routeId() + "（" + existing.establishedByTrainId() + "）冲突";
            }
        }

        // ---- 规则1：道岔位置检查 + 规则2：道岔锁死 ----
        OperationalLineData lineData = infrastructureCatalog.lineData();
        List<OperationalLineData.SwitchDefinition> involvedSwitches =
            lineData.switches() != null ? lineData.switches().stream()
                .filter(sw -> route.axleSegmentIds().contains(sw.normalSegmentId())
                    || route.axleSegmentIds().contains(sw.reverseSegmentId()))
                .toList() : List.of();

        for (OperationalLineData.SwitchDefinition swDef : involvedSwitches) {
            SwitchState sw = trackService.switchStates().stream()
                .filter(s -> s.id().equals(swDef.id()))
                .findFirst()
                .orElse(null);
            if (sw == null) continue;
            if (sw.locked()) {
                return "道岔 " + swDef.id() + " 已被锁死";
            }
            // 检查道岔是否在正确位置（默认要求定位 NORMAL）
            if (sw.position() != SwitchPosition.NORMAL) {
                return "道岔 " + swDef.id() + " 位置不对 (当前: " + sw.position() + ", 要求: NORMAL)";
            }
        }

        // ---- 规则2：锁死道岔 + 建立进路 ----
        Set<String> lockedIds = new HashSet<>();
        for (OperationalLineData.SwitchDefinition swDef : involvedSwitches) {
            trackService.lockSwitch(swDef.id());
            lockedIds.add(swDef.id());
        }

        RouteState established = new RouteState(
            routeId, RouteStatus.ESTABLISHED, lockedIds, trainId, route.axleSegmentIds()
        );
        routeStates.put(routeId, established);
        log.info("[联锁] 进路 {} 已建立 — 列车 {} — 锁死道岔: {}", routeId, trainId, lockedIds);
        return null; // 成功
    }

    /**
     * 释放进路（列车通过后调用）。
     * 解锁所有被该进路锁死的道岔。
     */
    public synchronized void releaseRoute(String routeId) {
        RouteState route = routeStates.get(routeId);
        if (route == null || route.status() != RouteStatus.ESTABLISHED) return;

        for (String switchId : route.lockedSwitchIds()) {
            trackService.unlockSwitch(switchId);
        }
        routeStates.put(routeId, route.withReleased());
        log.info("[联锁] 进路 {} 已释放，解锁道岔: {}", routeId, route.lockedSwitchIds());
    }

    /**
     * 释放某列车占用的所有进路（列车到达终点/异常时调用）。
     */
    public synchronized void releaseAllForTrain(String trainId) {
        routeStates.forEach((id, state) -> {
            if (trainId.equals(state.establishedByTrainId())) {
                releaseRoute(id);
            }
        });
    }

    // ==================== 查询接口 ====================

    /** 所有进路当前状态。 */
    public synchronized List<RouteState> states() {
        return List.copyOf(routeStates.values());
    }

    /** 指定进路状态。 */
    public synchronized RouteState state(String routeId) {
        return routeStates.getOrDefault(routeId,
            new RouteState(routeId, RouteStatus.AVAILABLE, Set.of(), null, Set.of()));
    }

    /**
     * 检查列车能否通过指定区段列表（用于 MA 计算中的进路约束）。
     * 返回 MA 应在哪个区段起点前截断（无约束返回 POSITIVE_INFINITY）。
     */
    public synchronized double maLimitFromRouteConflict(String trainId) {
        for (RouteState route : routeStates.values()) {
            if (route.status() != RouteStatus.ESTABLISHED) continue;
            if (trainId.equals(route.establishedByTrainId())) continue;
            // 找到冲突区段截断点
            for (String segId : route.axleSegmentIds()) {
                TrackSegmentState seg = trackService.states().stream()
                    .filter(s -> s.id().equals(segId))
                    .findFirst()
                    .orElse(null);
                if (seg != null && seg.occupancy() == TrackOccupancy.RESERVED) {
                    return seg.startMeters();
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    // ==================== 调度指令接入 ====================

    /**
     * 接收调度系统的进路相关指令并执行安全检查。
     * 调度说"走哪条路"，联锁管"能不能走"——如果被安全约束否决则返回拒绝原因。
     */
    public record RouteDispatchResult(boolean accepted, String rejectReason) {}

    public synchronized RouteDispatchResult applyDispatchCommand(String commandType, String detail, String trainId) {
        return switch (commandType) {
            case "REROUTE" -> {
                // detail 格式: {"fromStation":"S01","toStation":"S03"}
                // 查找匹配的进路
                String routeId = findBestRoute(detail);
                if (routeId == null) {
                    yield new RouteDispatchResult(false, "未找到匹配进路");
                }
                String rejection = establishRoute(routeId, trainId);
                yield rejection == null
                    ? new RouteDispatchResult(true, null)
                    : new RouteDispatchResult(false, rejection);
            }
            default -> new RouteDispatchResult(true, null); // 非进路指令不拦截
        };
    }

    private String findBestRoute(String detail) {
        // 简化实现：detail 中提取 station，匹配 RouteDefinition.name
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        if (routes == null || routes.isEmpty()) return null;
        return routes.stream()
            .filter(r -> detail != null && r.name() != null && detail.contains(r.name()))
            .map(OperationalLineData.RouteDefinition::id)
            .findFirst()
            .orElse(null);
    }
}
