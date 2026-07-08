package com.railwaysim.track;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
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

    private final List<TrackSegmentState> segments = new ArrayList<>();
    private final Map<String, SwitchState> switches = new HashMap<>();
    private final Set<String> reservedSegmentIds = new HashSet<>();
    private final Set<String> faultSegmentIds = new HashSet<>();
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
        segments.addAll(lineData.trackSegments().stream()
            .map(segment -> new TrackSegmentState(
                segment.id(),
                segment.startMeters(),
                segment.endMeters(),
                segment.defaultSpeedLimitMetersPerSecond(),
                TrackOccupancy.FREE
            ))
            .toList());
        switches.clear();
        if (lineData.switches() != null) {
            for (OperationalLineData.SwitchDefinition sw : lineData.switches()) {
                SwitchPosition defaultPos = "NORMAL".equalsIgnoreCase(sw.normalSegmentId()) || "NORMAL".equals(sw.directionCode())
                    ? SwitchPosition.NORMAL
                    : SwitchPosition.REVERSE;
                switches.put(sw.id(), new SwitchState(
                    sw.id(),
                    sw.mergeSegmentId(), // mergeSegmentId 在 YamlLineDataLoader 中被赋值为 node
                    defaultPos,
                    false,
                    sw.normalSegmentId()
                ));
            }
        }
        reservedSegmentIds.clear();
        faultSegmentIds.clear();
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

        // ② 全车长范围覆盖 → OCCUPIED
        for (TrainState train : trains) {
            double tail = train.positionMeters() - train.lengthMeters();
            double head = train.positionMeters();
            for (int i = 0; i < segments.size(); i++) {
                TrackSegmentState seg = segments.get(i);
                if (seg.occupancy() == TrackOccupancy.FAULT) {
                    continue; // 故障区段不受占用影响
                }
                if (overlaps(head, tail, seg.startMeters(), seg.endMeters())) {
                    segments.set(i, seg.withOccupancy(TrackOccupancy.OCCUPIED));
                }
            }
        }
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
     */
    public synchronized void injectFault(String segmentId) {
        faultSegmentIds.add(segmentId);
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.id().equals(segmentId)) {
                segments.set(i, seg.withOccupancy(TrackOccupancy.FAULT));
                log.warn("[Track] FAULT injected on segment {}", segmentId);
                return;
            }
        }
    }

    /**
     * 清除指定区段的故障，恢复为 FREE。
     *
     * @param segmentId 区段 ID
     */
    public synchronized void clearFault(String segmentId) {
        faultSegmentIds.remove(segmentId);
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState seg = segments.get(i);
            if (seg.id().equals(segmentId)) {
                segments.set(i, seg.withOccupancy(TrackOccupancy.FREE));
                log.info("[Track] FAULT cleared on segment {}", segmentId);
                return;
            }
        }
    }

    /** 当前所有故障区段 ID（只读视图）。 */
    public synchronized Set<String> faultSegmentIds() {
        return Collections.unmodifiableSet(new HashSet<>(faultSegmentIds));
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
                TrackSegmentState segment = segmentAt(train.positionMeters());
                double speedLimit = lineData.speedLimitAt(
                    train.positionMeters(),
                    segment.speedLimitMetersPerSecond()
                );
                double nextStationDistance = lineData.nextStationDistanceMeters(train.positionMeters());
                return new TrackConstraint(
                    train.id(),
                    segment.id(),
                    speedLimit,
                    lineData.gradientAt(train.positionMeters()),
                    simulationProperties.getDefaultCurveRadiusMeters(),
                    Double.isFinite(nextStationDistance)
                        ? nextStationDistance
                        : Math.max(0, segment.endMeters() - train.positionMeters())
                );
            })
            .toList();
    }

    /**
     * 按公里标查询所在区段。
     */
    public synchronized TrackSegmentState segmentAt(double positionMeters) {
        return segments.stream()
            .filter(seg -> positionMeters >= seg.startMeters() && positionMeters < seg.endMeters())
            .findFirst()
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
            TrackOccupancy.FREE
        );
    }
}
