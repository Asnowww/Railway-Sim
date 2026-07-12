package com.railwaysim.track;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 轨道仿真服务 — 控制层核心模块之一。
 *
 * <p>职责（按详细设计文档）：
 * <ul>
 *   <li>线路拓扑建模：从 {@link OperationalLineData} 初始化区段状态列表</li>
 *   <li>区段占用管理：全车长范围 [车尾, 车头] 覆盖区段 → OCCUPIED</li>
 *   <li>RESERVED 预留：SignalService 计算 MA 后标记，防止多车抢同一段轨道</li>
 *   <li>道岔管理：NORMAL/REVERSE 位置 + 锁闭标志</li>
 *   <li>故障注入：区段 → FAULT（故障—安全原则）</li>
 *   <li>线路数据查询：按公里标查限速/坡度/区段</li>
 * </ul>
 *
 * <p>区段状态优先级：FAULT > OCCUPIED > RESERVED > FREE
 *
 * <p>区段状态机（类比 MESI 缓存一致性协议）：
 * <pre>
 *   FREE(灰) ──MA预留──→ RESERVED(黄) ──车到达──→ OCCUPIED(红) ──车离开──→ FREE
 *                                                       ↑
 *                                                 故障注入 → FAULT(橙闪烁)
 * </pre>
 *
 * @author 黄旭涛 (信号 + 轨道仿真)
 */
@Service
public class TrackService {

    private static final Logger log = LoggerFactory.getLogger(TrackService.class);
    private static final double STATION_STOP_WINDOW_METERS = 10.0;

    private final List<TrackSegmentState> segments = new ArrayList<>();
    private final Map<String, SwitchState> switches = new HashMap<>();
    private final Set<String> reservedSegmentIds = new HashSet<>();
    private final Set<String> faultSegmentIds = new HashSet<>();
    private final Map<String, Set<String>> occupyingTrainIdsBySegment = new HashMap<>();
    private final Map<String, String> trainSegmentIds = new HashMap<>();
    private final Map<String, String> previousTrainSegmentIds = new HashMap<>();
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final SimulationProperties simulationProperties;

    public TrackService(
        StaticInfrastructureCatalog infrastructureCatalog,
        SimulationProperties simulationProperties
    ) {
        this.infrastructureCatalog = infrastructureCatalog;
        this.simulationProperties = simulationProperties;
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        segments.clear();
        // 从 YamlLineDataLoader 存的数据需要 YAML rawSeg 的 track 字段
        // 当前通过 segment.fromNode/fromNodeId 推断：同from的第二个区段为分支
        segments.addAll(lineData.trackSegments().stream()
            .map(segment -> new TrackSegmentState(
                segment.id(),
                segment.startMeters(),
                segment.endMeters(),
                segment.defaultSpeedLimitMetersPerSecond(),
                TrackOccupancy.FREE,
                segment.fromNodeId(),
                segment.toNodeId(),
                segment.track()
            ))
            .toList());
        switches.clear();
        if (lineData.switches() != null) {
            for (OperationalLineData.SwitchDefinition sw : lineData.switches()) {
                SwitchPosition defaultPos = "REVERSE".equalsIgnoreCase(sw.defaultPosition())
                    ? SwitchPosition.REVERSE
                    : SwitchPosition.NORMAL;
                String activeSegment = defaultPos == SwitchPosition.NORMAL
                    ? sw.normalSegmentId()
                    : sw.reverseSegmentId();
                switches.put(sw.id(), new SwitchState(
                    sw.id(),
                    sw.mergeSegmentId(),
                    defaultPos,
                    false,
                    activeSegment
                ));
            }
        }
        reservedSegmentIds.clear();
        faultSegmentIds.clear();
        occupyingTrainIdsBySegment.clear();
        trainSegmentIds.clear();
        previousTrainSegmentIds.clear();
    }

    // ==================== 区段占用计算 ====================

    /**
     * 根据列车位置更新区段占用状态。
     *
     * <p>算法：
     * <ol>
     *   <li>仅清除 OCCUPIED → FREE（保留 RESERVED 和 FAULT）</li>
     *   <li>遍历每列车，车尾=position-length，车头=position，区间重叠区段→OCCUPIED</li>
     *   <li>FAULT 和 OCCUPIED 优先级高于 RESERVED</li>
     * </ol>
     *
     * @param trains 当前所有列车状态列表
     */
    public synchronized void updateOccupancy(List<TrainState> trains) {
        occupyingTrainIdsBySegment.clear();
        Set<String> activeTrainIds = trains.stream().map(TrainState::id).collect(java.util.stream.Collectors.toSet());
        trainSegmentIds.keySet().retainAll(activeTrainIds);
        previousTrainSegmentIds.keySet().retainAll(activeTrainIds);

        // ① 清除 OCCUPIED → FREE（保留 RESERVED 和 FAULT）
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.occupancy() == TrackOccupancy.OCCUPIED) {
                // 如果该区段在 reserved 集合中，降为 RESERVED 而非 FREE
                if (reservedSegmentIds.contains(seg.id())) {
                    segments.set(i, seg.withOccupancy(TrackOccupancy.RESERVED));
                } else {
                    segments.set(i, seg.withOccupancy(TrackOccupancy.FREE));
                }
            }
        }

        // Resolve each train onto one topology path before applying body occupancy.
        for (TrainState train : trains) {
            // 终点站已停列车不再标记占用——后车可跟随进站
            if (train.positionMeters() >= infrastructureCatalog.lineData().lineLengthMeters() - 10
                && train.zeroSpeed()) {
                continue;
            }
            double tail = train.positionMeters() - train.lengthMeters();
            double head = train.positionMeters();
            TrackSegmentState current = segmentForTrain(train);
            String previousId = previousTrainSegmentIds.get(train.id());
            for (int i = 0; i < segments.size(); i++) {
                TrackSegmentState seg = segments.get(i);
                // 跳过不同轨道的并行段 (如列车在main, 不标north/loop/branch/depot)
                boolean sameTrack = seg.track() == null || current.track() == null
                    || seg.track().equals(current.track())
                    || ("main".equals(current.track()) && "main".equals(seg.track()));
                boolean onTrainPath = (seg.id().equals(current.id()) || seg.id().equals(previousId)) && sameTrack;
                if (onTrainPath && overlaps(head, tail, seg.startMeters(), seg.endMeters())) {
                    occupyingTrainIdsBySegment
                        .computeIfAbsent(seg.id(), ignored -> new HashSet<>())
                        .add(train.id());
                    if (seg.occupancy() != TrackOccupancy.FAULT) {
                        segments.set(i, seg.withOccupancy(TrackOccupancy.OCCUPIED));
                    }
                }
            }
        }
    }

    /** 返回当前实际覆盖指定区段的列车 ID 快照。 */
    public synchronized Set<String> occupyingTrainIds(String segmentId) {
        Set<String> trainIds = occupyingTrainIdsBySegment.get(segmentId);
        return trainIds == null ? Set.of() : Set.copyOf(trainIds);
    }

    public synchronized void assignTrainToSegment(String trainId, String segmentId) {
        if (trainId == null || trainId.isBlank() || !segmentExists(segmentId)) {
            throw new IllegalArgumentException("trainId and an existing segmentId are required");
        }
        trainSegmentIds.put(trainId, segmentId);
        previousTrainSegmentIds.remove(trainId);
    }

    public synchronized TrackSegmentState segmentForTrain(TrainState train) {
        TrackSegmentState assigned = stateById(trainSegmentIds.get(train.id()));
        if (assigned != null && contains(assigned, train.positionMeters())) {
            return assigned;
        }

        List<TrackSegmentState> candidates = segments.stream()
            .filter(segment -> contains(segment, train.positionMeters()))
            .toList();
        TrackSegmentState resolved = resolveNextSegment(assigned, candidates);
        if (resolved == null) {
            resolved = candidates.stream()
                .min(Comparator.comparingInt((TrackSegmentState segment) -> "main".equals(segment.track()) ? 0 : 1)
                    .thenComparing(TrackSegmentState::id))
                .orElseGet(this::fallbackSegment);
        }
        if (assigned != null && !assigned.id().equals(resolved.id())) {
            previousTrainSegmentIds.put(train.id(), assigned.id());
        }
        trainSegmentIds.put(train.id(), resolved.id());
        return resolved;
    }

    private TrackSegmentState resolveNextSegment(TrackSegmentState assigned, List<TrackSegmentState> candidates) {
        if (assigned == null || candidates.isEmpty()) {
            return null;
        }
        List<String> forward = forwardNeighborMap().getOrDefault(assigned.id(), List.of());
        List<TrackSegmentState> reachable = candidates.stream().filter(segment -> forward.contains(segment.id())).toList();
        if (reachable.isEmpty()) {
            return null;
        }
        Set<String> activeSwitchSegments = switches.values().stream()
            .map(SwitchState::activeSegmentId)
            .collect(java.util.stream.Collectors.toSet());
        return reachable.stream()
            .min(Comparator.comparingInt((TrackSegmentState segment) -> activeSwitchSegments.contains(segment.id()) ? 0 : 1)
                .thenComparingInt(segment -> assigned.track().equals(segment.track()) ? 0 : 1)
                .thenComparing(TrackSegmentState::id))
            .orElse(null);
    }

    private TrackSegmentState stateById(String segmentId) {
        if (segmentId == null) {
            return null;
        }
        return segments.stream().filter(segment -> segment.id().equals(segmentId)).findFirst().orElse(null);
    }

    private static boolean contains(TrackSegmentState segment, double positionMeters) {
        return positionMeters >= segment.startMeters() && positionMeters < segment.endMeters();
    }

    /**
     * SignalService 调用：MA 覆盖但车未到的区段标为 RESERVED。
     *
     * <p>调用时机：calculateAuthorities() 计算完 MA 之后。
     * 区段优先级 FAULT > OCCUPIED > RESERVED，不会覆盖已有高优先级状态。
     *
     * @param segmentIds MA 范围内应预留的区段 ID 集合
     */
    public synchronized void applyReservations(Set<String> segmentIds) {
        // 清除上一 tick 的 RESERVED
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.occupancy() == TrackOccupancy.RESERVED) {
                segments.set(i, seg.withOccupancy(TrackOccupancy.FREE));
            }
        }
        reservedSegmentIds.clear();
        reservedSegmentIds.addAll(segmentIds);

        // 设置新 RESERVED（仅 FREE 可变为 RESERVED）
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.occupancy() != TrackOccupancy.FREE) {
                continue; // OCCUPIED / FAULT 保持不变
            }
            if (reservedSegmentIds.contains(seg.id())) {
                segments.set(i, seg.withOccupancy(TrackOccupancy.RESERVED));
            }
        }
    }

    // ==================== 故障注入 ====================

    /**
     * 对指定区段注入故障 → FAULT 状态。
     *
     * <p>故障—安全原则：任何故障 → TrackSegmentState.occupancy = FAULT →
     * SignalService 检测后将前方信号降为 RED，MA 缩短至故障点前。
     *
     * @param segmentId 区段 ID
     * @return 本次调用是否改变了故障状态；区段不存在或重复注入时返回 false
     */
    public synchronized boolean injectFault(String segmentId) {
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.id().equals(segmentId)) {
                boolean changed = faultSegmentIds.add(segmentId)
                    || seg.occupancy() != TrackOccupancy.FAULT;
                segments.set(i, seg.withOccupancy(TrackOccupancy.FAULT));
                if (changed) {
                    log.warn("[Track] FAULT injected on segment {}", segmentId);
                }
                return changed;
            }
        }
        return false;
    }

    /**
     * 清除指定区段的故障，并按实时占用和预留状态恢复区段。
     *
     * @param segmentId 区段 ID
     * @return 本次调用是否改变了故障状态；区段不存在或没有故障时返回 false
     */
    public synchronized boolean clearFault(String segmentId) {
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.id().equals(segmentId)) {
                boolean changed = faultSegmentIds.remove(segmentId)
                    || seg.occupancy() == TrackOccupancy.FAULT;
                if (!changed) {
                    return false;
                }
                TrackOccupancy restored = !occupyingTrainIds(segmentId).isEmpty()
                    ? TrackOccupancy.OCCUPIED
                    : reservedSegmentIds.contains(segmentId)
                        ? TrackOccupancy.RESERVED
                        : TrackOccupancy.FREE;
                segments.set(i, seg.withOccupancy(restored));
                log.info("[Track] FAULT cleared on segment {}", segmentId);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean segmentExists(String segmentId) {
        return segmentId != null && segments.stream().anyMatch(segment -> segment.id().equals(segmentId));
    }

    /** 当前所有故障区段 ID（只读视图）。 */
    public synchronized Set<String> faultSegmentIds() {
        return Collections.unmodifiableSet(new HashSet<>(faultSegmentIds));
    }

    /**
     * 区段前向邻居映射（fromNode→toNode 方向）。
     * 从 OperationalLineData.TrackSegmentDefinition.forwardNeighborSegmentIds 构建。
     */
    public synchronized Map<String, List<String>> forwardNeighborMap() {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        Map<String, List<String>> map = new HashMap<>();
        for (OperationalLineData.TrackSegmentDefinition def : lineData.trackSegments()) {
            map.put(def.id(), def.forwardNeighborSegmentIds());
        }
        return map;
    }

    // ==================== 道岔管理 ====================

    /**
     * 扳动道岔到指定位置。
     *
     * <p>联锁规则 2：信号开放时道岔锁死，locked=true 时拒绝扳动。
     *
     * @param switchId 道岔 ID
     * @param position 目标位置 NORMAL/REVERSE
     * @return true 成功，false 拒绝（锁闭中）
     */
    public synchronized boolean throwSwitch(String switchId, SwitchPosition position) {
        SwitchState sw = switches.get(switchId);
        if (sw == null) {
            log.warn("[Track] Switch {} not found", switchId);
            return false;
        }
        if (sw.locked()) {
            log.warn("[Track] Switch {} is locked, cannot throw to {}", switchId, position);
            return false;
        }
        OperationalLineData.SwitchDefinition def = infrastructureCatalog.lineData().switches().stream()
            .filter(s -> s.id().equals(switchId))
            .findFirst()
            .orElse(null);
        String activeSegment = position == SwitchPosition.NORMAL
            ? (def != null ? def.normalSegmentId() : sw.activeSegmentId())
            : (def != null ? def.reverseSegmentId() : sw.activeSegmentId());
        switches.put(switchId, sw.withPosition(position).withActiveSegment(activeSegment));
        log.info("[Track] Switch {} thrown to {} (active segment: {})", switchId, position, activeSegment);
        return true;
    }

    /** 预检查道岔是否可以扳动（不实际扳动），用于联锁原子性验证。 */
    public synchronized boolean canThrowSwitch(String switchId, SwitchPosition position) {
        SwitchState sw = switches.get(switchId);
        if (sw == null) {
            return false;
        }
        if (sw.locked()) {
            return false;
        }
        // Already in the target position — no throw needed
        if (sw.position() == position) {
            return true;
        }
        OperationalLineData.SwitchDefinition def = infrastructureCatalog.lineData().switches().stream()
            .filter(s -> s.id().equals(switchId))
            .findFirst()
            .orElse(null);
        return def != null;
    }

    /** 锁闭道岔（联锁规则 2：信号开放时调用）。 */
    public synchronized void lockSwitch(String switchId) {
        SwitchState sw = switches.get(switchId);
        if (sw != null) {
            switches.put(switchId, sw.withLocked(true));
        }
    }

    /** 解锁道岔。 */
    public synchronized void unlockSwitch(String switchId) {
        SwitchState sw = switches.get(switchId);
        if (sw != null) {
            switches.put(switchId, sw.withLocked(false));
        }
    }

    /** 当前所有道岔状态（只读视图）。 */
    public synchronized List<SwitchState> switchStates() {
        return List.copyOf(switches.values());
    }

    // ==================== 查询接口 ====================

    /** 当前所有区段状态（只读视图）。 */
    public synchronized List<TrackSegmentState> states() {
        return List.copyOf(segments);
    }

    /**
     * 为每列车生成线路约束（限速/坡度/前方站点距离等），供 SignalService 和 TrainManager 使用。
     */
    public synchronized List<TrackConstraint> constraintsForTrains(List<TrainState> trains) {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        return trains.stream()
            .map(train -> {
                TrackSegmentState segment = segmentForTrain(train);
                double speedLimit = lineData.speedLimitAt(
                    train.positionMeters(),
                    segment.speedLimitMetersPerSecond()
                );
                double stationControlDistance = lineData.stationControlDistanceMeters(
                    train.positionMeters(),
                    STATION_STOP_WINDOW_METERS
                );
                return new TrackConstraint(
                    train.id(),
                    segment.id(),
                    speedLimit,
                    lineData.gradientAt(train.positionMeters()),
                    simulationProperties.getDefaultCurveRadiusMeters(),
                    // Keep the current station visible inside the platform stop window.
                    stationControlDistance
                );
            })
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * 按公里标查询所在区段。
     */
    public synchronized TrackSegmentState segmentAt(double positionMeters) {
        // 并行支线与正线里程重叠时，优先返回正线(main)，避免列车位置跳到侧线
        return segments.stream()
            .filter(seg -> positionMeters >= seg.startMeters() && positionMeters < seg.endMeters())
            .min(Comparator.comparingInt((TrackSegmentState s) -> "main".equals(s.track()) ? 0 : 1)
                .thenComparingDouble(TrackSegmentState::startMeters))
            .orElseGet(() -> segments.isEmpty()
                ? fallbackSegment()
                : segments.get(segments.size() - 1));
    }

    /**
     * 查询指定区段之后、距离最近的下一个 FAULT 区段起点（用于 MA 安全截断）。
     *
     * @param fromPositionMeters 起始公里标
     * @return 下一个故障区段起点公里标，无故障则返回 {@link Double#POSITIVE_INFINITY}
     */
    public synchronized double nextFaultPosition(double fromPositionMeters) {
        return segments.stream()
            .filter(seg -> seg.occupancy() == TrackOccupancy.FAULT)
            .filter(seg -> seg.startMeters() > fromPositionMeters)
            .mapToDouble(TrackSegmentState::startMeters)
            .min()
            .orElse(Double.POSITIVE_INFINITY);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 判断两个区间 [head, tail) 与 [segStart, segEnd) 是否有重叠。
     */
    private static boolean overlaps(double head, double tail, double segStart, double segEnd) {
        return head > segStart && tail < segEnd;
    }

    private TrackSegmentState fallbackSegment() {
        return new TrackSegmentState(
            "SEG-EMPTY",
            0,
            infrastructureCatalog.lineData().lineLengthMeters(),
            simulationProperties.getDefaultSpeedLimitMetersPerSecond(),
            TrackOccupancy.FREE,
            "", "", "main"
        );
    }
}
