package com.railwaysim.signal.vision;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.signal.SignalAspect;
import com.railwaysim.signal.SignalState;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.SignalService;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VisionUdpPacketBuilder {

    private static final int INVALID_DEPARTURE_DISPLAY = 0xffff;
    private static final int MAX_OWN_SPEED_MM_PER_SECOND = 33_333;

    private final Supplier<List<TrainState>> trainStatesSupplier;
    private final Supplier<List<SignalState>> signalStatesSupplier;
    private final Supplier<List<SwitchState>> switchStatesSupplier;
    private final Supplier<List<TrackSegmentState>> trackStatesSupplier;
    private final Supplier<List<MovementAuthority>> authoritiesSupplier;
    private final Function<Double, TrackSegmentState> segmentAt;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final VisionVehicleStateStore vehicleStateStore;
    private final AtomicInteger counter = new AtomicInteger();

    @Autowired
    public VisionUdpPacketBuilder(
        TrainManager trainManager,
        SignalService signalService,
        TrackService trackService,
        StaticInfrastructureCatalog infrastructureCatalog,
        VisionVehicleStateStore vehicleStateStore
    ) {
        this(
            trainManager::states,
            signalService::signalStates,
            trackService::switchStates,
            trackService::states,
            trackService::segmentAt,
            signalService::authorities,
            infrastructureCatalog,
            vehicleStateStore
        );
    }

    VisionUdpPacketBuilder(
        Supplier<List<TrainState>> trainStatesSupplier,
        Supplier<List<SignalState>> signalStatesSupplier,
        Supplier<List<SwitchState>> switchStatesSupplier,
        Supplier<List<TrackSegmentState>> trackStatesSupplier,
        Function<Double, TrackSegmentState> segmentAt,
        Supplier<List<MovementAuthority>> authoritiesSupplier,
        StaticInfrastructureCatalog infrastructureCatalog,
        VisionVehicleStateStore vehicleStateStore
    ) {
        this.authoritiesSupplier = authoritiesSupplier;
        this.trainStatesSupplier = trainStatesSupplier;
        this.signalStatesSupplier = signalStatesSupplier;
        this.switchStatesSupplier = switchStatesSupplier;
        this.trackStatesSupplier = trackStatesSupplier;
        this.segmentAt = segmentAt;
        this.infrastructureCatalog = infrastructureCatalog;
        this.vehicleStateStore = vehicleStateStore;
    }

    public VisionUdpPacket build(String trainId) {
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        List<TrainState> trains = trainStatesSupplier.get();
        Map<String, TrainState> trainById = trains.stream()
            .collect(Collectors.toMap(TrainState::id, Function.identity(), (left, right) -> right));
        TrainState selectedTrain = trainById.get(trainId);
        VisionVehicleState selectedVehicleState = vehicleStateStore.find(trainId).orElse(null);
        if (selectedTrain == null && selectedVehicleState == null) {
            throw new IllegalArgumentException("Train not found: " + trainId);
        }

        VisionTrainProjection selected = project(trainId, selectedTrain, selectedVehicleState);
        List<VisionTrainProjection> others = trains.stream()
            .filter(train -> !train.id().equals(trainId))
            .map(train -> project(train.id(), train, vehicleStateStore.find(train.id()).orElse(null)))
            .toList();
        List<Byte> signalStates = signalAspectBytes();
        List<Byte> switchStates = switchPositionBytes();
        int packetCounter = counter.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
        byte[] payload = encode(packetCounter, signalStates, switchStates, selected, others);
        return new VisionUdpPacket(
            selected.trainId(),
            packetCounter,
            signalStates.size(),
            switchStates.size(),
            others.size(),
            payload
        );
    }

    private byte[] encode(
        int packetCounter,
        List<Byte> signalStates,
        List<Byte> switchStates,
        VisionTrainProjection selected,
        List<VisionTrainProjection> others
    ) {
        int otherCount = Math.min(255, others.size());
        int payloadSize = 4
            + 1 + signalStates.size()
            + 1 + switchStates.size()
            + 4
            + 2
            + 1
            + 1
            + 4
            + 2
            + 1
            + 1
            + otherCount * (4 + 2 + 1 + 2);
        ByteBuffer buffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(packetCounter);
        buffer.put((byte) signalStates.size());
        signalStates.forEach(buffer::put);
        buffer.put((byte) switchStates.size());
        switchStates.forEach(buffer::put);
        buffer.putInt(clampInt(Math.round(selected.speedMetersPerSecond() * 1000), 0, MAX_OWN_SPEED_MM_PER_SECOND));
        buffer.putShort((short) selected.departureCountdownSeconds());
        buffer.put((byte) selected.operationCode());
        buffer.put((byte) selected.accelerationPercent());
        buffer.putInt(clampInt(Math.round(selected.headOffsetMeters() * 1000), 0, Integer.MAX_VALUE));
        buffer.putShort((short) selected.segmentNumber());
        buffer.put((byte) selected.directionCode());
        buffer.put((byte) otherCount);

        List<VisionTrainProjection> boundedOthers = others.subList(0, otherCount);
        for (VisionTrainProjection other : boundedOthers) {
            buffer.putInt(clampInt(Math.round(other.headOffsetMeters() * 1000), 0, Integer.MAX_VALUE));
        }
        for (VisionTrainProjection other : boundedOthers) {
            buffer.putShort((short) other.segmentNumber());
        }
        for (VisionTrainProjection other : boundedOthers) {
            buffer.put((byte) other.directionCode());
        }
        for (VisionTrainProjection other : boundedOthers) {
            buffer.putShort((short) clampInt(Math.round(other.speedMetersPerSecond() * 100), 0, 0xffff));
        }
        return buffer.array();
    }

    private VisionTrainProjection project(String trainId, TrainState train, VisionVehicleState vehicleState) {
        double position = vehicleState != null && vehicleState.headPositionMeters() != null
            ? vehicleState.headPositionMeters()
            : train == null ? 0 : train.positionMeters();
        TrackSegmentState segment = resolveSegment(trainId, position, vehicleState, train);
        double speed = vehicleState != null && vehicleState.speedMetersPerSecond() != null
            ? vehicleState.speedMetersPerSecond()
            : train == null ? 0 : train.speedMetersPerSecond();
        double acceleration = vehicleState != null && vehicleState.accelerationMetersPerSecondSquared() != null
            ? vehicleState.accelerationMetersPerSecondSquared()
            : train == null ? 0 : train.accelerationMetersPerSecondSquared();
        int accelerationPercent = vehicleState != null && vehicleState.accelerationPercent() != null
            ? vehicleState.accelerationPercent()
            : clampInt(Math.round(acceleration / 1.1 * 100), -100, 100);
        int directionCode = vehicleState != null && vehicleState.directionCode() != null
            ? vehicleState.directionCode()
            : directionCode(train);
        VisionRunCondition runCondition = vehicleState != null && vehicleState.runCondition() != null
            ? vehicleState.runCondition()
            : runCondition(train);
        VisionHeadlightState headlightState = vehicleState != null && vehicleState.headlightState() != null
            ? vehicleState.headlightState()
            : VisionHeadlightState.HIGH;
        int operationCode = vehicleState != null && vehicleState.operationCode() != null
            ? vehicleState.operationCode()
            : operationCode(runCondition, headlightState);
        int departureCountdown = vehicleState != null && vehicleState.departureCountdownSeconds() != null
            ? vehicleState.departureCountdownSeconds()
            : INVALID_DEPARTURE_DISPLAY;
        return new VisionTrainProjection(
            trainId,
            segmentOffsetMeters(position, segment.id()),
            segment.id(),
            segmentNumber(segment.id()),
            Math.max(0, speed),
            acceleration,
            accelerationPercent,
            directionCode,
            runCondition,
            headlightState,
            operationCode,
            departureCountdown
        );
    }

    private TrackSegmentState resolveSegment(String trainId, double position, VisionVehicleState vehicleState, TrainState train) {
        if (vehicleState != null && vehicleState.headSegmentId() != null) {
            TrackSegmentState matching = trackStatesSupplier.get().stream()
                .filter(segment -> segment.id().equals(vehicleState.headSegmentId()))
                .findFirst()
                .orElse(null);
            if (matching != null) {
                return matching;
            }
        }
        // 信号系统的 MA.currentSegmentId 是列车所在段的权威（联锁按拓扑推进），优先使用；
        // 双线线路（9号线）上下行区段里程重叠，仅按里程/方向匹配会拿错股道边号。
        String maSegment = authoritiesSupplier.get().stream()
            .filter(authority -> authority.trainId().equals(trainId))
            .map(MovementAuthority::currentSegmentId)
            .filter(id -> id != null && !id.isBlank())
            .findFirst()
            .orElse(null);
        if (maSegment != null) {
            TrackSegmentState bySignal = trackStatesSupplier.get().stream()
                .filter(segment -> segment.id().equals(maSegment))
                .findFirst()
                .orElse(null);
            if (bySignal != null) {
                return bySignal;
            }
        }
        // 回退：按列车方向所属股道匹配（注意：当前引擎 direction=DOWN 实为里程递增，
        // 否则下行车会被匹配到上行边号发给视景系统。track 命名约定 up/down 对应 direction UP/DOWN。
        String direction = train != null ? train.direction() : null;
        if (direction != null && !direction.isBlank()) {
            TrackSegmentState onTrack = trackStatesSupplier.get().stream()
                .filter(segment -> direction.equalsIgnoreCase(segment.track()))
                .filter(segment -> position >= segment.startMeters() && position <= segment.endMeters())
                .findFirst()
                .orElse(null);
            if (onTrack != null) {
                return onTrack;
            }
        }
        return segmentAt.apply(position);
    }

    private List<Byte> signalAspectBytes() {
        List<SignalState> states = signalStatesSupplier.get();
        List<OperationalLineData.SignalDefinition> definitions = infrastructureCatalog.lineData().signals();
        return VisionProtocolV13Layout.SIGNAL_IDS.stream()
            .map(protocolId -> definitions.stream()
                .filter(definition -> matchesProtocolId(protocolId, definition.interoperabilityId(), definition.id(), definition.name()))
                .findFirst()
                .orElse(null))
            .map(definition -> signalState(definition, states))
            .map(state -> state == null ? aspectByte(SignalAspect.RED) : aspectByte(state.aspect()))
            .toList();
    }

    private List<Byte> switchPositionBytes() {
        List<SwitchState> states = switchStatesSupplier.get();
        List<OperationalLineData.SwitchDefinition> definitions = infrastructureCatalog.lineData().switches();
        return VisionProtocolV13Layout.SWITCH_IDS.stream()
            .map(protocolId -> definitions.stream()
                .filter(definition -> matchesProtocolId(protocolId, definition.interoperabilityId(), definition.id(), definition.name()))
                .findFirst()
                .orElse(null))
            .map(definition -> switchState(definition, states))
            .map(state -> state == null ? (byte) 0x01 : switchByte(state.position()))
            .toList();
    }

    private SignalState signalState(
        OperationalLineData.SignalDefinition definition,
        List<SignalState> states
    ) {
        if (definition == null) {
            return null;
        }
        return states.stream()
            .filter(state -> state.signalId().equals(definition.id()))
            .findFirst()
            .or(() -> states.stream().filter(state -> state.segmentId().equals(definition.segmentId())).findFirst())
            .orElse(null);
    }

    private SwitchState switchState(
        OperationalLineData.SwitchDefinition definition,
        List<SwitchState> states
    ) {
        if (definition == null) {
            return null;
        }
        return states.stream().filter(state -> state.id().equals(definition.id())).findFirst().orElse(null);
    }

    private boolean matchesProtocolId(String protocolId, String... candidates) {
        for (String candidate : candidates) {
            if (protocolId.equals(normalizeProtocolId(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeProtocolId(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toUpperCase();
        return normalized.startsWith("0X") ? normalized.substring(2) : normalized;
    }

    private byte aspectByte(SignalAspect aspect) {
        return switch (aspect) {
            case RED -> 0x01;
            case GREEN -> 0x02;
            case YELLOW -> 0x10;
        };
    }

    private byte switchByte(SwitchPosition position) {
        return (byte) (position == SwitchPosition.REVERSE ? 0x02 : 0x01);
    }

    private int segmentNumber(String segmentId) {
        Map<String, OperationalLineData.TrackSegmentDefinition> byId = infrastructureCatalog.lineData().trackSegmentById();
        OperationalLineData.TrackSegmentDefinition definition = byId.get(segmentId);
        int protocolIndex = VisionProtocolV13Layout.SECTION_IDS.indexOf(normalizeProtocolId(segmentId));
        if (protocolIndex >= 0) {
            return protocolIndex + 1;
        }
        if (definition != null && definition.rawSegmentId() > 0) {
            return clampInt(definition.rawSegmentId(), 0, 0xffff);
        }
        Map<String, Integer> indexById = new LinkedHashMap<>();
        List<OperationalLineData.TrackSegmentDefinition> segments = infrastructureCatalog.lineData().trackSegments();
        for (int index = 0; index < segments.size(); index++) {
            indexById.put(segments.get(index).id(), index + 1);
        }
        return clampInt(indexById.getOrDefault(segmentId, 0), 0, 0xffff);
    }

    private double segmentOffsetMeters(double absolutePositionMeters, String segmentId) {
        OperationalLineData.TrackSegmentDefinition definition = infrastructureCatalog.lineData()
            .trackSegmentById()
            .get(segmentId);
        if (definition == null) {
            return Math.max(0, absolutePositionMeters);
        }
        return Math.max(0, Math.min(definition.lengthMeters(), absolutePositionMeters - definition.startMeters()));
    }

    private int directionCode(TrainState train) {
        if (train == null || train.direction() == null) {
            return 1;
        }
        return "UP".equals(train.direction()) ? -1 : 1;
    }

    private VisionRunCondition runCondition(TrainState train) {
        if (train == null) {
            return VisionRunCondition.COAST;
        }
        if ("SERVICE".equals(train.brakeState()) || "REGENERATIVE".equals(train.brakeState()) || "EMERGENCY".equals(train.brakeState()) || train.brakeForceNewtons() > 0) {
            return VisionRunCondition.BRAKE;
        }
        if ("APPLYING".equals(train.tractionState()) || "DERATED".equals(train.tractionState()) || train.tractionForceNewtons() > 0) {
            return VisionRunCondition.TRACTION;
        }
        return VisionRunCondition.COAST;
    }

    private int operationCode(VisionRunCondition runCondition, VisionHeadlightState headlightState) {
        // 协议把运行工况和头灯扩展压在同 1 byte，当前优先按给定工况码输出；
        // 若真实视景系统需要头灯复合码，可通过 REST 上报 operationCode 覆盖。
        return switch (runCondition) {
            case TRACTION -> 0x11;
            case BRAKE -> 0x12;
            case COAST -> 0x13;
        };
    }

    private int clampInt(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
