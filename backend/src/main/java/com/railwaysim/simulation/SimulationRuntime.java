package com.railwaysim.simulation;

import com.railwaysim.api.SimulationWebSocketHandler;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.dispatch.integration.DispatchCommandPublisher;
import com.railwaysim.monitor.MonitorService;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.simulation.event.DomainEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SimulationRuntime {

    private static final Logger log = LoggerFactory.getLogger(SimulationRuntime.class);

    private final TrainManager trainManager;
    private final TrackService trackService;
    private final SignalService signalService;
    private final PowerService powerService;
    private final DispatchService dispatchService;
    private final DispatchCommandPublisher dispatchCommandPublisher;
    private final MonitorService monitorService;
    private final SimulationWebSocketHandler webSocketHandler;
    private final SimulationProperties simulationProperties;
    private final SimpleEventBus eventBus;
    private final RealtimeStateCache realtimeStateCache;
    private final SimulationPersistenceService persistenceService;
    private final RouteInterlockingService interlockingService;
    private List<DomainEvent> lastEvents = List.of();
    private long tick;
    private SimulationStatus status = SimulationStatus.STOPPED;
    private Instant simulatedTime = Instant.now();
    private long lastPushAtMillis;

    public SimulationRuntime(
        TrainManager trainManager,
        TrackService trackService,
        SignalService signalService,
        PowerService powerService,
        DispatchService dispatchService,
        DispatchCommandPublisher dispatchCommandPublisher,
        MonitorService monitorService,
        SimulationWebSocketHandler webSocketHandler,
        SimulationProperties simulationProperties,
        SimpleEventBus eventBus,
        RealtimeStateCache realtimeStateCache,
        SimulationPersistenceService persistenceService,
        RouteInterlockingService interlockingService
    ) {
        this.trainManager = trainManager;
        this.trackService = trackService;
        this.signalService = signalService;
        this.powerService = powerService;
        this.dispatchService = dispatchService;
        this.dispatchCommandPublisher = dispatchCommandPublisher;
        this.monitorService = monitorService;
        this.webSocketHandler = webSocketHandler;
        this.simulationProperties = simulationProperties;
        this.eventBus = eventBus;
        this.realtimeStateCache = realtimeStateCache;
        this.persistenceService = persistenceService;
        this.interlockingService = interlockingService;
    }

    public synchronized SimulationSnapshot snapshot() {
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot start() {
        status = SimulationStatus.RUNNING;
        return advanceOneTick();
    }

    public synchronized SimulationSnapshot tick() {
        if (status == SimulationStatus.STOPPED) {
            status = SimulationStatus.RUNNING;
        }
        return advanceOneTick();
    }

    public synchronized SimulationSnapshot pause() {
        status = SimulationStatus.PAUSED;
        return buildSnapshot();
    }

    public synchronized SimulationSnapshot reset() {
        tick = 0;
        status = SimulationStatus.STOPPED;
        simulatedTime = Instant.now();
        lastPushAtMillis = 0;
        dispatchService.reset();
        trainManager.reset();
        trackService.reset();
        signalService.reset();
        interlockingService.reset();
        powerService.reset();
        realtimeStateCache.clear();
        eventBus.drain();
        lastEvents = List.of();
        return buildSnapshot();
    }

    private SimulationSnapshot advanceOneTick() {
        tick++;
        simulatedTime = simulatedTime.plusMillis(simulationProperties.getTickMillis());
        TickContext context = new TickContext(
            tick,
            simulationProperties.getTickMillis(),
            simulationProperties.getTickMillis() / 1000.0,
            simulatedTime
        );

        List<TrainState> beforeTrainStates = trainManager.states();
        trackService.updateOccupancy(beforeTrainStates);
        List<TrackConstraint> trackConstraints = trackService.constraintsForTrains(beforeTrainStates);
        signalService.calculateAuthorities(beforeTrainStates, trackConstraints, List.of());
        dispatchService.evaluate(context, beforeTrainStates, signalService.authorities());

        // 拦截 REROUTE 调度指令 → 交联锁处理（在约束计算前）
        List<DispatchCommand> rerouteCmds = dispatchService.drainCommandsOfType("REROUTE");
        for (DispatchCommand cmd : rerouteCmds) {
            var result = interlockingService.applyDispatchCommand(cmd.commandType(), commandDetail(cmd), cmd.trainId());
            if (!result.accepted()) {
                log.warn("[Runtime] 联锁拒绝调度指令 {}: {}", cmd.id(), result.rejectReason());
            }
        }

        // 发车指令 — 调度→信号→车辆：联锁检查进路→创建列车→列车进场自动建进路
        handleDepartures(dispatchService.drainCommandsOfType("DEPART"));

        List<DispatchCommand> generatedCommands = dispatchService.drainCommands();
        if (!generatedCommands.isEmpty()) {
            dispatchCommandPublisher.publish(generatedCommands);
        }

        List<DispatchConstraint> dispatchConstraints = dispatchService.constraintsForTrains(beforeTrainStates);
        signalService.calculateAuthorities(beforeTrainStates, trackConstraints, dispatchConstraints);
        List<PowerConstraint> powerConstraints = powerService.constraintsForTrains(beforeTrainStates);

        List<VehiclePhysicsOutput> outputs = trainManager.tickAll(
            context,
            signalService.authorities(),
            trackConstraints,
            dispatchConstraints,
            powerConstraints
        );
        powerService.updateFromVehicleOutputs(outputs);
        trackService.updateOccupancy(trainManager.states());
        signalService.recomputeSignalAspects(); // 基于最终区段占用刷新灯色
        lastEvents = eventBus.drain();
        persistIfDue(context);

        SimulationSnapshot snapshot = buildSnapshot();
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastPushAtMillis >= simulationProperties.getPushIntervalMillis()) {
            webSocketHandler.broadcast(snapshot);
            lastPushAtMillis = nowMillis;
        }
        return snapshot;
    }

    private String commandDetail(DispatchCommand command) {
        if (command.payload() != null) {
            Object detail = command.payload().get("detail");
            if (detail != null) {
                return detail.toString();
            }
        }
        return command.reason();
    }

    /**
     * 处理调度发来的发车指令。
     *
     * <p>链路：调度时刻表触发 → DEPART指令 → 信号联锁检查 → 车辆层创建列车。
     * 发车分三步：
     * <ol>
     *   <li>解析 payload 中的列车参数（trainNo/linkId/offsetMeters/direction）</li>
     *   <li>联锁检查：进路是否可用（不实际建立，建路由后续 touchRoutes 自动完成）</li>
     *   <li>调车辆层创建列车 → 列车进场 → 下一 tick 的 touchRoutes 自动建进路 + MA</li>
     * </ol>
     *
     * <p>DEPART 命令的 payload 格式：
     * <pre>{@code
     *   {"trainNo":3, "linkId":1, "offsetMeters":0,
     *    "fromStation":"S01", "toStation":"S05", "direction":"DOWN"}
     * }</pre>
     */
    private void handleDepartures(List<DispatchCommand> departCommands) {
        for (DispatchCommand cmd : departCommands) {
            Map<String, Object> payload = cmd.payload();
            if (payload == null) {
                log.warn("[Runtime] DEPART command {} has no payload, skipped", cmd.id());
                continue;
            }
            int trainNo = intFromPayload(payload, "trainNo", 0);
            if (trainNo <= 0) {
                log.warn("[Runtime] DEPART command {} missing trainNo", cmd.id());
                continue;
            }
            String trainId = "TR-%03d".formatted(trainNo);
            int linkId = intFromPayload(payload, "linkId", 1);
            double offsetMeters = doubleFromPayload(payload, "offsetMeters", 0);
            String dirStr = stringFromPayload(payload, "direction", "DOWN");
            ExternalTrainDirection direction = "UP".equalsIgnoreCase(dirStr)
                ? ExternalTrainDirection.UP : ExternalTrainDirection.DOWN;

            // 安全门：如果列车已存在，跳过
            if (trainManager.states().stream().anyMatch(t -> t.id().equals(trainId))) {
                log.info("[Runtime] Train {} already exists, skip DEPART", trainId);
                continue;
            }

            // 联锁安全门：确认起止站间至少有一条进路可用
            String fromStation = stringFromPayload(payload, "fromStation", null);
            String toStation = stringFromPayload(payload, "toStation", null);
            if (fromStation != null && toStation != null) {
                String routeDetail = "{\"fromStation\":\"%s\",\"toStation\":\"%s\"}".formatted(fromStation, toStation);
                var result = interlockingService.applyDispatchCommand("REROUTE", routeDetail, trainId);
                if (!result.accepted()) {
                    log.warn("[Runtime] 联锁拒绝发车 {}: {}", trainId, result.rejectReason());
                    continue;
                }
            }

            // 创建列车 — 信号→车辆转译
            SignalTrainLifecycleTrainSpec spec = new SignalTrainLifecycleTrainSpec(
                trainNo, linkId, offsetMeters, direction
            );
            try {
                trainManager.applyLifecycleCommand(SignalTrainLifecycleCommand.add(List.of(spec)));
                log.info("[Runtime] 发车 {} — trainNo={} linkId={} offset={}m direction={}",
                    trainId, trainNo, linkId, offsetMeters, direction);
            } catch (Exception e) {
                log.error("[Runtime] 发车失败 {}: {}", trainId, e.getMessage());
            }
        }
    }

    private static int intFromPayload(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return fallback;
    }

    private static double doubleFromPayload(Map<String, Object> payload, String key, double fallback) {
        Object value = payload.get(key);
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {} }
        return fallback;
    }

    private static String stringFromPayload(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value != null ? value.toString() : fallback;
    }

    private void persistIfDue(TickContext context) {
        long persistenceStepMillis = simulationProperties.getPersistenceStepMillis();
        long tickMillis = simulationProperties.getTickMillis();
        if (persistenceStepMillis <= 0 || tickMillis <= 0) {
            return;
        }
        long elapsedMillis = context.tick() * tickMillis;
        if (elapsedMillis % persistenceStepMillis == 0) {
            persistenceService.persist(
                context,
                trainManager.states(),
                powerService.states(),
                lastEvents
            );
            persistenceService.persistTrackOccupancy(context, trackService.states());
            persistenceService.persistSignalStates(context, signalService.authorities());
        }
    }

    private SimulationSnapshot buildSnapshot() {
        return monitorService.buildSnapshot(
            tick,
            simulatedTime,
            status,
            trainManager.states(),
            trackService.states(),
            signalService.authorities(),
            signalService.signalStates(),
            trackService.switchStates(),
            interlockingService.states(),
            powerService.states(),
            lastEvents,
            dispatchService.snapshot()
        );
    }
}
