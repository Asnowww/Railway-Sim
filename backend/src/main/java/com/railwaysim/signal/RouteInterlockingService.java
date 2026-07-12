package com.railwaysim.signal;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RouteInterlockingService {

    private static final Logger log = LoggerFactory.getLogger(RouteInterlockingService.class);

    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final TrackService trackService;
    private final Map<String, RouteState> routeStates = new LinkedHashMap<>();
    private final Map<String, String> routeHoldsByTrain = new LinkedHashMap<>();

    public RouteInterlockingService(
        StaticInfrastructureCatalog infrastructureCatalog,
        TrackService trackService
    ) {
        this.infrastructureCatalog = infrastructureCatalog;
        this.trackService = trackService;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        Map<String, RouteState> expiredStates = new LinkedHashMap<>();
        for (RouteState route : routeStates.values()) {
            for (String switchId : route.lockedSwitchIds()) {
                trackService.unlockSwitch(switchId);
            }
            RouteState expired = route.status() == RouteStatus.EXPIRED_BY_RESET
                ? route
                : route.transitionTo(RouteStatus.EXPIRED_BY_RESET);
            expiredStates.put(route.routeId(), expired);
        }
        routeStates.clear();
        routeHoldsByTrain.clear();
        OperationalLineData lineData = infrastructureCatalog.lineData();
        if (lineData.routes() == null) {
            return;
        }
        for (OperationalLineData.RouteDefinition routeDef : lineData.routes()) {
            Set<String> axleSegmentIds = new LinkedHashSet<>(
                routeDef.axleSectionIds() == null ? List.of() : routeDef.axleSectionIds()
            );
            RouteState expired = expiredStates.get(routeDef.id());
            RouteState available = expired == null
                ? new RouteState(routeDef.id(), RouteStatus.AVAILABLE, Set.of(), null, axleSegmentIds)
                : new RouteState(routeDef.id(), RouteStatus.EXPIRED_BY_RESET, Set.of(), null, axleSegmentIds)
                    .transitionTo(RouteStatus.AVAILABLE);
            routeStates.put(routeDef.id(), available);
        }
    }

    public synchronized String establishRoute(String routeId, String trainId) {
        RouteState route = routeStates.get(routeId);
        if (route == null) {
            return "Route " + routeId + " does not exist";
        }
        if (trainId == null || trainId.isBlank()) {
            return "trainId is required";
        }
        if (route.status().holdsInterlockingResources()) {
            if (trainId.equals(route.establishedByTrainId())
                && route.status() != RouteStatus.RELEASING) {
                routeHoldsByTrain.remove(trainId);
                return null;
            }
            return "Route " + routeId + " is " + route.status()
                + " for train " + route.establishedByTrainId();
        }
        if (route.status() != RouteStatus.AVAILABLE) {
            if (route.status() == RouteStatus.CONFLICTED) {
                restoreResolvedConflicts();
                route = routeStates.get(routeId);
            }
            if (route.status() != RouteStatus.AVAILABLE
                && route.status().returnsToAvailableOnTick()) {
                route = transition(route, RouteStatus.AVAILABLE);
            }
            if (route.status() != RouteStatus.AVAILABLE) {
                return "Route " + routeId + " is not available: " + route.status();
            }
        }

        route = transition(route, RouteStatus.VALIDATING);

        Set<String> routeSegments = resolvedSegmentIds(route);
        if (routeSegments.isEmpty()) {
            return reject(route, RouteStatus.REJECTED,
                "Route " + routeId + " has no resolved track segments");
        }

        for (RouteState existing : routeStates.values()) {
            if (!existing.routeId().equals(routeId)
                && existing.status().holdsInterlockingResources()
                && intersects(routeSegments, resolvedSegmentIds(existing))) {
                return reject(route, RouteStatus.CONFLICTED,
                    "Route " + routeId + " conflicts with established route " + existing.routeId());
            }
        }

        // ---- 区段占用/故障检查（不在计划中的区段禁止建进路） ----
        for (String segId : routeSegments) {
            TrackSegmentState seg = findSegmentById(segId).orElse(null);
            if (seg == null) {
                return reject(route, RouteStatus.REJECTED,
                    "TRACK_NOT_FOUND:" + segId + " does not exist");
            }
            if (seg.occupancy() == TrackOccupancy.FAULT) {
                return reject(route, RouteStatus.REJECTED,
                    "TRACK_FAULT:" + segId + " is in fault state");
            }
            Set<String> occupyingTrainIds = trackService.occupyingTrainIds(segId);
            // 正线进路允许多车追踪（MA 处理间距），仅非正线拒绝他车占用
            boolean isMainRoute = isMainTypeRoute(routeId);
            if (!isMainRoute && occupyingTrainIds.stream().anyMatch(id -> !trainId.equals(id))) {
                return reject(route, RouteStatus.REJECTED,
                    "TRACK_OCCUPIED:" + segId + " is occupied by another train");
            }
            if (seg.occupancy() == TrackOccupancy.OCCUPIED && occupyingTrainIds.isEmpty()) {
                return reject(route, RouteStatus.REJECTED,
                    "TRACK_OCCUPIED:" + segId + " has no attributable occupying train");
            }
        }

        Optional<String> invalidSwitchRequirement = invalidSwitchRequirement(routeSegments);
        if (invalidSwitchRequirement.isPresent()) {
            return reject(route, RouteStatus.REJECTED, invalidSwitchRequirement.get());
        }

        Map<OperationalLineData.SwitchDefinition, SwitchPosition> switchRequirements =
            switchRequirements(routeSegments);
        // Pre-check all switches before throwing any (partial rollback safety)
        for (Map.Entry<OperationalLineData.SwitchDefinition, SwitchPosition> entry : switchRequirements.entrySet()) {
            OperationalLineData.SwitchDefinition swDef = entry.getKey();
            SwitchPosition required = entry.getValue();
            SwitchState sw = switchState(swDef.id()).orElse(null);
            if (sw == null) {
                continue;
            }
            if (sw.locked()) {
                return reject(route, RouteStatus.REJECTED, "Switch " + swDef.id() + " is locked");
            }
            if (sw.position() != required && !trackService.canThrowSwitch(swDef.id(), required)) {
                return reject(route, RouteStatus.REJECTED,
                    "Switch " + swDef.id() + " cannot move to " + required);
            }
        }

        route = transition(route, RouteStatus.SETTING_SWITCHES);
        Set<String> lockedIds = new LinkedHashSet<>();
        try {
            for (Map.Entry<OperationalLineData.SwitchDefinition, SwitchPosition> entry : switchRequirements.entrySet()) {
                OperationalLineData.SwitchDefinition swDef = entry.getKey();
                SwitchPosition required = entry.getValue();
                SwitchState sw = switchState(swDef.id()).orElse(null);
                if (sw == null) {
                    continue;
                }
                if (sw.position() != required) {
                    trackService.throwSwitch(swDef.id(), required);
                }
                trackService.lockSwitch(swDef.id());
                lockedIds.add(swDef.id());
            }
        } catch (RuntimeException exception) {
            for (String switchId : lockedIds) {
                trackService.unlockSwitch(switchId);
            }
            routeStates.put(routeId, route.transitionTo(RouteStatus.FAILED));
            log.error("[Interlocking] failed to establish route {}", routeId, exception);
            return "ROUTE_FAILED:" + routeId + " could not set and lock switches";
        }

        routeStates.put(routeId, route.withLocked(lockedIds, trainId));
        routeHoldsByTrain.remove(trainId);
        log.info("[Interlocking] route {} established by train {}; locked switches={}", routeId, trainId, lockedIds);
        return null;
    }

    public synchronized void holdTrainUntilRouteEstablished(String trainId, String reason) {
        if (trainId == null || trainId.isBlank()) {
            return;
        }
        routeHoldsByTrain.put(trainId, reason == null || reason.isBlank() ? "ROUTE_NOT_LOCKED" : reason);
    }

    public synchronized void clearRouteHold(String trainId) {
        routeHoldsByTrain.remove(trainId);
    }

    public synchronized boolean isRouteHoldActive(String trainId) {
        return routeHoldsByTrain.containsKey(trainId);
    }

    public synchronized String routeHoldReason(String trainId) {
        return routeHoldsByTrain.getOrDefault(trainId, "ROUTE_NOT_LOCKED");
    }

    public synchronized void releaseRoute(String routeId) {
        RouteState route = routeStates.get(routeId);
        if (route == null) {
            return;
        }
        if (route.status() == RouteStatus.LOCKED || route.status() == RouteStatus.OCCUPIED) {
            route = transition(route, RouteStatus.RELEASING);
        }
        if (route.status() != RouteStatus.RELEASING) {
            return;
        }

        completeRelease(route);
    }

    public synchronized String cancelRoute(String routeId) {
        RouteState route = routeStates.get(routeId);
        if (route == null) {
            return "Route " + routeId + " does not exist";
        }
        if (route.status() == RouteStatus.AVAILABLE
            || route.status() == RouteStatus.RELEASED
            || route.status() == RouteStatus.CANCELLED) {
            return null;
        }
        if (route.status() == RouteStatus.OCCUPIED || route.status() == RouteStatus.RELEASING) {
            return "Route " + routeId + " cannot be cancelled after a train has entered";
        }
        if (route.status() != RouteStatus.LOCKED
            && route.status() != RouteStatus.VALIDATING
            && route.status() != RouteStatus.SETTING_SWITCHES) {
            return "Route " + routeId + " cannot be cancelled from " + route.status();
        }

        for (String switchId : route.lockedSwitchIds()) {
            trackService.unlockSwitch(switchId);
        }
        routeStates.put(routeId, route.transitionTo(RouteStatus.CANCELLED));
        restoreResolvedConflicts();
        log.info("[Interlocking] route {} cancelled; unlocked switches={}", routeId, route.lockedSwitchIds());
        return null;
    }

    public synchronized void releaseAllForTrain(String trainId) {
        routeHoldsByTrain.remove(trainId);
        List<String> routeIds = routeStates.values().stream()
            .filter(state -> trainId.equals(state.establishedByTrainId()))
            .map(RouteState::routeId)
            .toList();
        routeIds.forEach(this::releaseRoute);
    }

    public synchronized List<RouteState> states() {
        return List.copyOf(routeStates.values());
    }

    public synchronized RouteState state(String routeId) {
        return routeStates.getOrDefault(routeId,
            new RouteState(routeId, RouteStatus.AVAILABLE, Set.of(), null, Set.of()));
    }

    public synchronized double maLimitFromRouteConflict(String trainId) {
        // 查找当前 tick 中本列车所在的区段（用于判断是否在同一线路上）
        TrackSegmentState trainSeg = null;
        for (TrackSegmentState seg : trackService.states()) {
            if (seg.occupancy() == TrackOccupancy.OCCUPIED) {
                // 简单判断：如果有列车在这个区段上，该区段属于同一线路
                // 后续可改为精确匹配 trainId
                trainSeg = seg;
                break;
            }
        }

        double closest = Double.POSITIVE_INFINITY;
        for (RouteState route : routeStates.values()) {
            if (!route.status().holdsInterlockingResources()) {
                continue;
            }
            if (trainId.equals(route.establishedByTrainId())) {
                continue;
            }

            // 如果列车正在此进路的区段上 → 同线路追踪，不冲突
            boolean onThisRoute = trainSeg != null
                && resolvedSegmentIds(route).contains(trainSeg.id());
            if (onThisRoute) {
                continue;
            }

            for (String segId : resolvedSegmentIds(route)) {
                TrackSegmentState seg = findSegmentById(segId).orElse(null);
                if (seg != null && seg.occupancy() == TrackOccupancy.RESERVED) {
                    if (seg.startMeters() < closest) {
                        closest = seg.startMeters();
                    }
                }
            }
        }
        return closest;
    }

    public synchronized List<String> establishedSegmentPathForTrain(String trainId) {
        return routeStates.values().stream()
            .filter(route -> route.status().holdsInterlockingResources())
            .filter(route -> trainId.equals(route.establishedByTrainId()))
            .findFirst()
            .map(route -> List.copyOf(resolvedSegmentIds(route)))
            .orElse(List.of());
    }

    public synchronized void touchRoutes(List<TrainState> trains) {
        ensureInitialized();
        restoreResolvedConflicts();
        for (RouteState route : List.copyOf(routeStates.values())) {
            if (route.status().returnsToAvailableOnTick()) {
                transition(route, RouteStatus.AVAILABLE);
            }
        }

        // 前车优先建立进路：避免后车因前车占用区段被拒，而前车未及时建路
        List<TrainState> sorted = new ArrayList<>(trains);
        sorted.sort(Comparator.comparingDouble(TrainState::positionMeters).reversed());

        for (TrainState train : sorted) {
            for (RouteState route : List.copyOf(routeStates.values())) {
                if (route.status() != RouteStatus.AVAILABLE) {
                    continue;
                }
                if (headInRoute(train, route)) {
                    String rejection = establishRoute(route.routeId(), train.id());
                    if (rejection == null) {
                        log.info("[Interlocking] auto-established route {} for train {}", route.routeId(), train.id());
                        break; // one route per train per tick
                    }
                }
            }
        }

        for (RouteState route : List.copyOf(routeStates.values())) {
            if (route.status() == RouteStatus.LOCKED && ownerTrainInRoute(trains, route)) {
                transition(route, RouteStatus.OCCUPIED);
            } else if (route.status() == RouteStatus.OCCUPIED && !ownerTrainInRoute(trains, route)) {
                transition(route, RouteStatus.RELEASING);
            } else if (route.status() == RouteStatus.RELEASING) {
                completeRelease(route);
            }
        }
    }

    public record RouteDispatchResult(boolean accepted, String rejectReason) {}

    public synchronized RouteDispatchResult applyDispatchCommand(String commandType, String detail, String trainId) {
        return switch (commandType) {
            case "REROUTE", "REQUEST_ROUTE" -> {
                String routeId = findBestRoute(detail);
                if (routeId == null) {
                    yield new RouteDispatchResult(false, "No matching route for detail=" + detail);
                }
                String rejection = establishRoute(routeId, trainId);
                yield rejection == null
                    ? new RouteDispatchResult(true, null)
                    : new RouteDispatchResult(false, rejection);
            }
            case "CANCEL_ROUTE" -> {
                String routeId = findBestRoute(detail);
                if (routeId == null) {
                    yield new RouteDispatchResult(false, "No matching route for detail=" + detail);
                }
                String rejection = cancelRoute(routeId);
                yield rejection == null
                    ? new RouteDispatchResult(true, null)
                    : new RouteDispatchResult(false, rejection);
            }
            default -> new RouteDispatchResult(false, "Unsupported route command: " + commandType);
        };
    }

    private RouteState transition(RouteState route, RouteStatus target) {
        RouteState next = route.transitionTo(target);
        routeStates.put(route.routeId(), next);
        return next;
    }

    private String reject(RouteState route, RouteStatus target, String reason) {
        transition(route, target);
        return reason;
    }

    private boolean isMainTypeRoute(String routeId) {
        return infrastructureCatalog.lineData().routes().stream()
            .filter(r -> r.id().equals(routeId))
            .findFirst()
            .map(r -> "MAIN".equalsIgnoreCase(r.typeCode()))
            .orElse(false);
    }

    /**
     * 判断占用列车是否在前方同线追踪（允许MA处理间距，不需拒绝进路）。
     */
    private boolean isTrainOnRoutePath(String occupyingTrainId, Set<String> routeSegs, String requestingTrainId) {
        // 只对同线路追踪放行：占用列车与请求列车在同一进路区段集合内
        for (TrackSegmentState seg : trackService.states()) {
            if (routeSegs.contains(seg.id())
                && seg.occupancy() == TrackOccupancy.OCCUPIED
                && trackService.occupyingTrainIds(seg.id()).contains(occupyingTrainId)) {
                return true;
            }
        }
        return false;
    }

    private void completeRelease(RouteState route) {
        for (String switchId : route.lockedSwitchIds()) {
            trackService.unlockSwitch(switchId);
        }
        RouteState released = transition(route, RouteStatus.RELEASED);
        restoreResolvedConflicts();
        log.info("[Interlocking] route {} released; unlocked switches={}",
            released.routeId(), route.lockedSwitchIds());
    }

    private void ensureInitialized() {
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        if (routeStates.isEmpty() && routes != null && !routes.isEmpty()) {
            reset();
        }
    }

    private boolean headInRoute(TrainState train, RouteState route) {
        // 用 segmentAt 确定列车实际在哪个段(主线优先),避免并行支线里程重叠误判
        TrackSegmentState actualSeg = trackService.segmentAt(train.positionMeters());
        if (actualSeg == null) return false;
        Set<String> routeSegIds = resolvedSegmentIds(route);
        return routeSegIds.contains(actualSeg.id());
    }

    private boolean ownerTrainInRoute(List<TrainState> trains, RouteState route) {
        return trains.stream()
            .filter(train -> train.id().equals(route.establishedByTrainId()))
            .anyMatch(train -> trainInRoute(train, route));
    }

    private boolean trainInRoute(TrainState train, RouteState route) {
        Set<String> segmentIds = resolvedSegmentIds(route);
        return segmentIds.stream()
            .map(this::findSegmentById)
            .flatMap(Optional::stream)
            .anyMatch(seg -> {
                double tail = train.positionMeters() - train.lengthMeters();
                return train.positionMeters() > seg.startMeters() && tail < seg.endMeters();
            });
    }

    private void restoreResolvedConflicts() {
        List<RouteState> established = routeStates.values().stream()
            .filter(route -> route.status().holdsInterlockingResources())
            .toList();
        for (RouteState route : List.copyOf(routeStates.values())) {
            if (route.status() != RouteStatus.CONFLICTED) {
                continue;
            }
            Set<String> routeSegments = resolvedSegmentIds(route);
            boolean stillConflicts = established.stream()
                .anyMatch(existing -> intersects(routeSegments, resolvedSegmentIds(existing)));
            if (!stillConflicts) {
                transition(route, RouteStatus.AVAILABLE);
            }
        }
    }

    private Map<OperationalLineData.SwitchDefinition, SwitchPosition> switchRequirements(Set<String> routeSegments) {
        Map<OperationalLineData.SwitchDefinition, SwitchPosition> requirements = new LinkedHashMap<>();
        List<OperationalLineData.SwitchDefinition> switches = infrastructureCatalog.lineData().switches();
        if (switches == null) {
            return requirements;
        }
        for (OperationalLineData.SwitchDefinition sw : switches) {
            boolean usesNormal = routeSegments.contains(sw.normalSegmentId());
            boolean usesReverse = routeSegments.contains(sw.reverseSegmentId());
            if (usesNormal && usesReverse) {
                log.warn("[Interlocking] switch {} used in both NORMAL and REVERSE within route — skipping lock", sw.id());
            } else if (usesNormal) {
                requirements.put(sw, SwitchPosition.NORMAL);
            } else if (usesReverse) {
                requirements.put(sw, SwitchPosition.REVERSE);
            }
        }
        return requirements;
    }

    private Optional<String> invalidSwitchRequirement(Set<String> routeSegments) {
        List<OperationalLineData.SwitchDefinition> switches = infrastructureCatalog.lineData().switches();
        if (switches == null) {
            return Optional.empty();
        }
        for (OperationalLineData.SwitchDefinition sw : switches) {
            boolean usesNormal = routeSegments.contains(sw.normalSegmentId());
            boolean usesReverse = routeSegments.contains(sw.reverseSegmentId());
            if (usesNormal && usesReverse) {
                String reason = "Route requires switch " + sw.id() + " in both NORMAL and REVERSE";
                log.warn("[Interlocking] {}", reason);
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }

    private Set<String> resolvedSegmentIds(RouteState route) {
        Set<String> ids = new LinkedHashSet<>();
        for (String rawId : route.axleSegmentIds()) {
            resolveSegmentId(rawId).ifPresent(ids::add);
        }
        return ids;
    }

    private Optional<String> resolveSegmentId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        if (findSegmentById(rawId).isPresent()) {
            return Optional.of(rawId);
        }
        String suffix = numericSuffix(rawId);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        return trackService.states().stream()
            .map(TrackSegmentState::id)
            .filter(id -> suffix.equals(numericSuffix(id)))
            .findFirst();
    }

    private Optional<TrackSegmentState> findSegmentById(String id) {
        return trackService.states().stream()
            .filter(segment -> segment.id().equals(id))
            .findFirst();
    }

    private Optional<SwitchState> switchState(String id) {
        return trackService.switchStates().stream()
            .filter(sw -> sw.id().equals(id))
            .findFirst();
    }

    // ==================== 路线选择 ====================

    /**
     * 查询所有可用进路信息，供调度系统获取路线列表和当前状态。
     */
    public record RouteInfo(String routeId, String name, String type,
                            String fromStation, String toStation,
                            List<String> segmentIds, String status) {}

    public synchronized List<RouteInfo> queryRoutes() {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        List<OperationalLineData.StationDefinition> stations = lineData.stations();
        List<OperationalLineData.RouteDefinition> routes = lineData.routes();
        if (routes == null) return List.of();

        return routes.stream().map(route -> {
            String fromSt = findStationNameForSignal(route.startSignalId(), stations);
            String toSt = findStationNameForSignal(route.endSignalId(), stations);
            RouteState state = routeStates.getOrDefault(route.id(),
                new RouteState(route.id(), RouteStatus.AVAILABLE, Set.of(), null, new LinkedHashSet<>()));
            return new RouteInfo(
                route.id(), route.name(), route.typeCode(),
                fromSt, toSt,
                route.axleSectionIds(), state.status().name()
            );
        }).toList();
    }

    /**
     * 根据调度传入的 detail 查找匹配进路。支持三种格式：
     * <ul>
     *   <li>{"routeId":"R_MAIN"} — 直接指定进路ID</li>
     *   <li>{"fromStation":"S01","toStation":"S03"} — 按起止站点选路</li>
     *   <li>{"from":"S01","to":"S03"} — 同上（简写）</li>
     * </ul>
     *
     * <p>选路策略：多个可选进路时，优先选 MAIN 类型（正线），其次选区段数最少的（最短路径）。
     */
    private String findBestRoute(String detail) {
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        if (routes == null || routes.isEmpty()) {
            return null;
        }

        // ① 直接指定 routeId（纯 ID 或 JSON 字段）
        if (detail != null && routeStates.containsKey(detail.trim())) {
            return detail.trim();
        }
        String directId = parseJsonField(detail, "routeId");
        if (directId != null && routeStates.containsKey(directId)) {
            return directId;
        }

        // ② 按起止站名
        String fromStation = parseJsonField(detail, "fromStation");
        String toStation = parseJsonField(detail, "toStation");
        if (fromStation == null) fromStation = parseJsonField(detail, "from");
        if (toStation == null) toStation = parseJsonField(detail, "to");

        if (fromStation != null && toStation != null) {
            return findRouteByStations(fromStation, toStation);
        }

        // ③ 按起止信号机
        String fromSignal = parseJsonField(detail, "fromSignal");
        String toSignal = parseJsonField(detail, "toSignal");
        if (fromSignal != null && toSignal != null) {
            return findRouteBySignals(fromSignal, toSignal);
        }

        // ④ 兜底：detail 中包含进路名
        return routes.stream()
            .filter(route -> detail != null && route.name() != null && detail.contains(route.name()))
            .map(OperationalLineData.RouteDefinition::id)
            .findFirst()
            .orElse(null);
    }

    private String findRouteByStations(String fromStation, String toStation) {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        List<OperationalLineData.SignalDefinition> signals = lineData.signals();
        List<OperationalLineData.StationDefinition> stations = lineData.stations();

        String fromSignal = findSignalNearStation(fromStation, stations, signals);
        String toSignal = findSignalNearStation(toStation, stations, signals);

        if (fromSignal != null && toSignal != null) {
            return findRouteBySignals(fromSignal, toSignal);
        }
        return null;
    }

    private String findRouteBySignals(String startSignalId, String endSignalId) {
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        if (routes == null || routes.isEmpty()) return null;

        List<OperationalLineData.RouteDefinition> matches = routes.stream()
            .filter(r -> startSignalId.equals(r.startSignalId()) && endSignalId.equals(r.endSignalId()))
            .toList();
        if (matches.isEmpty()) {
            matches = routes.stream()
                .filter(r -> r.startSignalId() != null && r.startSignalId().contains(startSignalId)
                    && r.endSignalId() != null && r.endSignalId().contains(endSignalId))
                .toList();
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0).id();

        // 多条：优先 MAIN → 最短路径
        return matches.stream()
            .min((a, b) -> {
                boolean aMain = "MAIN".equalsIgnoreCase(a.typeCode());
                boolean bMain = "MAIN".equalsIgnoreCase(b.typeCode());
                if (aMain != bMain) return aMain ? -1 : 1;
                return a.axleSectionIds().size() - b.axleSectionIds().size();
            })
            .map(OperationalLineData.RouteDefinition::id)
            .orElse(matches.get(0).id());
    }

    private String findSignalNearStation(String stationIdOrName,
                                          List<OperationalLineData.StationDefinition> stations,
                                          List<OperationalLineData.SignalDefinition> signals) {
        if (stations == null || signals == null || signals.isEmpty()) return null;
        OperationalLineData.StationDefinition station = stations.stream()
            .filter(s -> s.id().equals(stationIdOrName)
                || (s.name() != null && s.name().equals(stationIdOrName)))
            .findFirst()
            .orElse(null);
        if (station == null) return null;

        // 收集进路起终点信号ID作为优选集合(正线信号优先)
        List<OperationalLineData.RouteDefinition> routes = infrastructureCatalog.lineData().routes();
        Set<String> routeSignalIds = routes != null
            ? routes.stream()
                .filter(r -> "MAIN".equalsIgnoreCase(r.typeCode()))
                .flatMap(r -> java.util.stream.Stream.of(r.startSignalId(), r.endSignalId()))
                .collect(java.util.stream.Collectors.toSet())
            : Set.of();

        return signals.stream()
            .min((a, b) -> {
                double da = Math.abs(a.positionMeters() - station.centerMeters());
                double db = Math.abs(b.positionMeters() - station.centerMeters());
                // 优先选择正线进路信号(+1000m惩罚非进路信号)
                if (!routeSignalIds.contains(a.id())) da += 1000;
                if (!routeSignalIds.contains(b.id())) db += 1000;
                return Double.compare(da, db);
            })
            .map(OperationalLineData.SignalDefinition::id)
            .orElse(null);
    }

    private String findStationNameForSignal(String signalId,
                                             List<OperationalLineData.StationDefinition> stations) {
        if (signalId == null || stations == null || stations.isEmpty()) return "?";
        OperationalLineData.SignalDefinition sig = infrastructureCatalog.lineData().signals().stream()
            .filter(s -> signalId.equals(s.id()))
            .findFirst().orElse(null);
        if (sig == null) return "?";
        return stations.stream()
            .min((a, b) -> Double.compare(
                Math.abs(a.centerMeters() - sig.positionMeters()),
                Math.abs(b.centerMeters() - sig.positionMeters())))
            .map(OperationalLineData.StationDefinition::name)
            .orElse("?");
    }

    /** 从简易 JSON 串中提取字段值 */
    private static String parseJsonField(String json, String key) {
        if (json == null || key == null) return null;
        String pattern = "\"" + key + "\"";
        int ki = json.indexOf(pattern);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + pattern.length());
        if (colon < 0) return null;
        String remainder = json.substring(colon + 1).trim();
        if (remainder.startsWith("\"")) {
            int end = remainder.indexOf('"', 1);
            return end > 0 ? remainder.substring(1, end) : remainder.substring(1);
        }
        int end = 0;
        while (end < remainder.length() && remainder.charAt(end) != ',' && remainder.charAt(end) != '}') end++;
        return remainder.substring(0, end).trim();
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String id : left) {
            if (right.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static String numericSuffix(String value) {
        return value.replaceAll("[^0-9]", "");
    }
}
