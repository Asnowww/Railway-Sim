package com.railwaysim.vehicle.external;

import com.railwaysim.config.ExternalSimulatorProperties;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vehicle.SignalVehicleCommand;
import com.railwaysim.signal.vision.VisionVehicleStateRequest;
import com.railwaysim.signal.vision.VisionVehicleStateStore;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.drivercab.DriverCabDirectionHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabMasterHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabStateSnapshot;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/**
 * 手动指挥外部仿真系统（RT-LAB 模型机）常驻桥，不经 9300 车辆运行时：
 * <pre>
 * 实物司控台 46B → 中央镜像(DriverCabStateSnapshot)
 *   → 本桥 20ms 周期编码"指令+百分比"UDP 320B → 模型机 23001
 * 模型机 20ms 遥测 480B → 本桥常驻 23002 接收 → 最新遥测缓存 + 视景状态仓库
 * </pre>
 * 协议依据《（四）车辆系统》2 UDP 通信接口规范：小端，20 车槽位，
 * 指令 1=加速/2=减速/0=惰行；遥测 加速度/速度/累计里程 各 double。
 */
@Service
public class ExternalSimManualBridge implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExternalSimManualBridge.class);

    /** 桥控制模式：AUTO=跟随司机台快照的 atoModeActive 位；MANUAL/ATO=前端 REST 强制指定。 */
    public enum BridgeMode { AUTO, MANUAL, ATO }

    private final ExternalSimulatorProperties properties;
    private final TrainManager trainManager;
    private final SignalService signalService;
    private final VisionVehicleStateStore visionStateStore;
    private final ExternalUdpPacketCodec codec = new ExternalUdpPacketCodec();
    private volatile BridgeMode modeOverride = BridgeMode.AUTO;
    private volatile String activeMode = "MANUAL";

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sentPackets = new AtomicLong();
    private final AtomicLong sendErrors = new AtomicLong();
    private final AtomicLong receivedPackets = new AtomicLong();
    private final AtomicLong receiveErrors = new AtomicLong();
    private final Map<Integer, ExternalTrainTelemetry> latestTelemetry = new ConcurrentHashMap<>();
    private volatile Instant lastSentAt;
    private volatile Instant lastReceivedAt;
    private volatile String lastError = "";
    private volatile int lastCommand;
    private volatile double lastPercent;

    private DatagramSocket socket;
    private ScheduledExecutorService sender;
    private Thread receiver;

    public ExternalSimManualBridge(
        ExternalSimulatorProperties properties,
        TrainManager trainManager,
        SignalService signalService,
        VisionVehicleStateStore visionStateStore
    ) {
        this.properties = properties;
        this.trainManager = trainManager;
        this.signalService = signalService;
        this.visionStateStore = visionStateStore;
    }

    public void setModeOverride(BridgeMode mode) {
        this.modeOverride = mode == null ? BridgeMode.AUTO : mode;
    }

    public BridgeMode modeOverride() {
        return modeOverride;
    }

    @Override
    public void start() {
        if (!properties.getManualBridge().isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        ExternalSimulatorProperties.UdpProperties udp = properties.getUdp();
        try {
            // 绑定通配地址上的平台端口，避免部署机多网卡时源地址不匹配。
            socket = new DatagramSocket(udp.getPlatformPort());
            socket.setSoTimeout(500);
        } catch (IOException exception) {
            running.set(false);
            lastError = "bind failed: " + exception.getMessage();
            log.warn("External sim manual bridge failed to bind UDP {}: {}", udp.getPlatformPort(), lastError);
            return;
        }
        receiver = new Thread(this::receiveLoop, "external-sim-bridge-rx");
        receiver.setDaemon(true);
        receiver.start();
        sender = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "external-sim-bridge-tx");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.getManualBridge().getSendIntervalMillis();
        sender.scheduleAtFixedRate(this::sendCommands, 0, interval, TimeUnit.MILLISECONDS);
        log.info("External sim manual bridge started: platform UDP :{} -> model {}:{} interval={}ms train={}",
            udp.getPlatformPort(), udp.getModelHost(), udp.getModelPort(), interval,
            properties.getManualBridge().getTrainId());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (sender != null) {
            sender.shutdownNow();
        }
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void sendCommands() {
        try {
            String trainId = properties.getManualBridge().getTrainId();
            DriverCabStateSnapshot cab = trainManager.state(trainId)
                .map(TrainState::driverCabState)
                .orElse(null);
            boolean ato = modeOverride == BridgeMode.ATO
                || (modeOverride == BridgeMode.AUTO && cab != null && cab.atoModeActive());
            activeMode = ato ? "ATO" : "MANUAL";
            ExternalTrainCommand command = ato ? commandFromAto(cab) : commandFromDriverCab(cab);
            byte[] payload = codec.encodeCommands(List.of(command), properties.getMaxTrains());
            ExternalSimulatorProperties.UdpProperties udp = properties.getUdp();
            socket.send(new DatagramPacket(
                payload, payload.length,
                InetAddress.getByName(udp.getModelHost()), udp.getModelPort()
            ));
            sentPackets.incrementAndGet();
            lastSentAt = Instant.now();
            lastCommand = command.command();
            lastPercent = command.percent();
        } catch (IOException | RuntimeException exception) {
            long errors = sendErrors.incrementAndGet();
            lastError = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
            if (errors == 1 || errors % 500 == 0) {
                log.warn("External sim manual bridge send failed (count={}): {}", errors, lastError);
            }
        }
    }

    /**
     * ATO 指令律：以外部遥测的速度/累计里程为反馈，中央 MA 为约束。
     * 剩余授权不足制动距离或超速 → 减速；低于目标限速 90% → 加速；否则惰行。
     * 无 MA 时按无授权处理（全常用制动）。
     */
    private ExternalTrainCommand commandFromAto(DriverCabStateSnapshot cab) {
        String trainId = properties.getManualBridge().getTrainId();
        int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(trainId, properties.getMaxTrains());
        if (cab != null && !cab.keySwitchLocked()) {
            // 钥匙拔出：车辆防护语义，常用制动到停。
            return new ExternalTrainCommand(trainNo, 2, 100, 1, 0, ExternalTrainDirection.UP, 1);
        }
        MovementAuthority authority = signalService.authorities().stream()
            .filter(candidate -> trainId.equals(candidate.trainId()))
            .findFirst()
            .orElse(null);
        ExternalTrainTelemetry telemetry = latestTelemetry.get(trainNo);
        double speed = telemetry == null ? 0 : Math.max(0, telemetry.speed());
        double position = telemetry == null ? 0 : Math.max(0, telemetry.cumulativeDistance());
        if (authority == null) {
            return new ExternalTrainCommand(trainNo, speed > 0.1 ? 2 : 0, speed > 0.1 ? 100 : 0,
                1, 0, ExternalTrainDirection.UP, 1);
        }
        double limit = Math.max(0, authority.speedLimitMetersPerSecond());
        double remaining = authority.authorityEndMeters() - position;
        // 常用制动 0.9 m/s²，外加 10m + 1s 反应余量。
        double brakingDistance = speed * speed / (2 * 0.9) + 10 + speed;
        if (remaining <= 0 || speed > limit + 0.5) {
            return new ExternalTrainCommand(trainNo, 2, 100, 1, 0, ExternalTrainDirection.UP, 1);
        }
        if (remaining <= brakingDistance) {
            double urgency = Math.min(1, brakingDistance / Math.max(remaining, 1));
            return new ExternalTrainCommand(trainNo, 2, Math.min(100, 40 + 60 * urgency),
                1, 0, ExternalTrainDirection.UP, 1);
        }
        if (speed < limit * 0.9) {
            double gap = (limit - speed) / Math.max(limit, 0.1);
            return new ExternalTrainCommand(trainNo, 1, Math.max(20, Math.min(80, gap * 100)),
                1, 0, ExternalTrainDirection.UP, 1);
        }
        return new ExternalTrainCommand(trainNo, 0, 0, 1, 0, ExternalTrainDirection.UP, 1);
    }

    /** 司控台镜像手柄 → 协议指令：牵引=1、制动/快速制动=2、零位=0（惰行）。 */
    private ExternalTrainCommand commandFromDriverCab(DriverCabStateSnapshot cab) {
        String trainId = properties.getManualBridge().getTrainId();
        int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(trainId, properties.getMaxTrains());
        if (cab == null || !cab.keySwitchLocked()) {
            return new ExternalTrainCommand(trainNo, 0, 0, 1, 0, ExternalTrainDirection.UNKNOWN, 1);
        }
        int command;
        double percent;
        DriverCabMasterHandleState master = cab.masterHandleState();
        if (master == DriverCabMasterHandleState.TRACTION) {
            command = 1;
            percent = cab.tractionNotchPercent();
        } else if (master == DriverCabMasterHandleState.BRAKE) {
            command = 2;
            percent = cab.brakeNotchPercent();
        } else if (master == DriverCabMasterHandleState.FAST_BRAKE) {
            command = 2;
            percent = 100;
        } else {
            command = 0;
            percent = 0;
        }
        ExternalTrainDirection direction = cab.directionHandleState() == DriverCabDirectionHandleState.BACKWARD
            ? ExternalTrainDirection.DOWN : ExternalTrainDirection.UP;
        return new ExternalTrainCommand(trainNo, command, percent, 1, 0, direction, 1);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[codec.telemetryPacketBytes(properties.getMaxTrains())];
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                List<ExternalTrainTelemetry> telemetry = codec.decodeTelemetry(
                    Arrays.copyOf(packet.getData(), packet.getLength()), properties.getMaxTrains());
                receivedPackets.incrementAndGet();
                lastReceivedAt = Instant.now();
                for (ExternalTrainTelemetry entry : telemetry) {
                    latestTelemetry.put(entry.trainNo(), entry);
                }
                publishVisionState();
            } catch (SocketTimeoutException ignored) {
                // 常驻收包：超时仅用于响应停止信号。
            } catch (IOException | RuntimeException exception) {
                if (running.get()) {
                    receiveErrors.incrementAndGet();
                    lastError = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                }
            }
        }
    }

    /** 把本车遥测镜像进视景状态仓库，使视景 UDP 输出跟随外部仿真运动。 */
    private void publishVisionState() {
        String trainId = properties.getManualBridge().getTrainId();
        int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(trainId, properties.getMaxTrains());
        ExternalTrainTelemetry telemetry = latestTelemetry.get(trainNo);
        if (telemetry == null) {
            return;
        }
        String runCondition = lastCommand == 1 ? "TRACTION" : lastCommand == 2 ? "BRAKE" : "COAST";
        visionStateStore.put(trainId, new VisionVehicleStateRequest(
            Math.max(0, telemetry.speed()),
            telemetry.acceleration(),
            null,
            Math.max(0, telemetry.cumulativeDistance()),
            null,
            telemetry.direction() == ExternalTrainDirection.DOWN ? -1 : 1,
            runCondition,
            null,
            null,
            null
        ));
    }

    public Map<String, Object> status() {
        ExternalSimulatorProperties.UdpProperties udp = properties.getUdp();
        String trainId = properties.getManualBridge().getTrainId();
        int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(trainId, properties.getMaxTrains());
        ExternalTrainTelemetry telemetry = latestTelemetry.get(trainNo);
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("enabled", properties.getManualBridge().isEnabled());
        status.put("running", running.get());
        status.put("modeOverride", modeOverride.name());
        status.put("activeMode", activeMode);
        status.put("trainId", trainId);
        status.put("trainNo", trainNo);
        status.put("modelTarget", udp.getModelHost() + ":" + udp.getModelPort());
        status.put("platformPort", udp.getPlatformPort());
        status.put("sendIntervalMillis", properties.getManualBridge().getSendIntervalMillis());
        status.put("sentPackets", sentPackets.get());
        status.put("sendErrors", sendErrors.get());
        status.put("receivedPackets", receivedPackets.get());
        status.put("receiveErrors", receiveErrors.get());
        status.put("lastSentAt", lastSentAt);
        status.put("lastReceivedAt", lastReceivedAt);
        status.put("lastCommand", lastCommand);
        status.put("lastPercent", lastPercent);
        status.put("lastError", lastError);
        if (telemetry != null) {
            status.put("telemetry", Map.of(
                "speedMetersPerSecond", telemetry.speed(),
                "accelerationMetersPerSecondSquared", telemetry.acceleration(),
                "cumulativeDistanceMeters", telemetry.cumulativeDistance()
            ));
        }
        return status;
    }
}
