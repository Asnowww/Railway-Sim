package com.railwaysim.signal.vision;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.signal.SignalAspect;
import com.railwaysim.signal.SignalState;
import com.railwaysim.signal.SignalService;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
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
        StaticInfrastructureCatalog infrastructureCatalog,
        VisionVehicleStateStore vehicleStateStore
    ) {
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
        buffer.putInt(clampInt(Math.round(selected.headPositionMeters() * 1000), 0, Integer.MAX_VALUE));
        buffer.putShort((short) selected.segmentNumber());
        buffer.put((byte) selected.directionCode());
        buffer.put((byte) otherCount);

        List<VisionTrainProjection> boundedOthers = others.subList(0, otherCount);
        for (VisionTrainProjection other : boundedOthers) {
            buffer.putInt(clampInt(Math.round(other.headPositionMeters() * 1000), 0, Integer.MAX_VALUE));
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
        TrackSegmentState segment = resolveSegment(position, vehicleState);
        double speed = vehicleState != null && vehicleState.speedMetersPerSecond() != null
            ? vehicleState.speedMetersPerSecond()
            : train == null ? 0 : train.speedMetersPerSecond();
        double acceleration = vehicleState != null && vehicleState.accelerationMetersPerSecondSquared() != null
            ? vehicleState.accelerationMetersPerSecondSquared()
            : train == null ? 0 : train.accelerationMetersPerSecondSquared();
        int accelerationPercent = vehicleState != null && vehicleState.accelerationPercent() != null
            ? vehicleState.accelerationPercent()
            : clampInt(Math.round(Math.abs(acceleration) / 1.1 * 100), 0, 100);
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
            Math.max(0, position),
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

    private TrackSegmentState resolveSegment(double position, VisionVehicleState vehicleState) {
        if (vehicleState != null && vehicleState.headSegmentId() != null) {
            TrackSegmentState matching = trackStatesSupplier.get().stream()
                .filter(segment -> segment.id().equals(vehicleState.headSegmentId()))
                .findFirst()
                .orElse(null);
            if (matching != null) {
                return matching;
            }
        }
        return segmentAt.apply(position);
    }

    private List<Byte> signalAspectBytes() {
        Map<String, SignalState> signalBySegment = signalStatesSupplier.get().stream()
            .collect(Collectors.toMap(SignalState::segmentId, Function.identity(), (left, right) -> left));
        List<OperationalLineData.SignalDefinition> definitions = infrastructureCatalog.lineData().signals();
        if (!definitions.isEmpty()) {
            return definitions.stream()
                .map(definition -> signalBySegment.get(definition.segmentId()))
                .map(state -> state == null ? aspectByte(SignalAspect.GREEN) : aspectByte(state.aspect()))
                .toList();
        }
        return signalStatesSupplier.get().stream()
            .sorted(Comparator.comparingDouble(SignalState::positionMeters))
            .map(state -> aspectByte(state.aspect()))
            .toList();
    }

    private List<Byte> switchPositionBytes() {
        Map<String, SwitchState> stateById = switchStatesSupplier.get().stream()
            .collect(Collectors.toMap(SwitchState::id, Function.identity(), (left, right) -> left));
        List<OperationalLineData.SwitchDefinition> definitions = infrastructureCatalog.lineData().switches();
        if (!definitions.isEmpty()) {
            return definitions.stream()
                .map(definition -> stateById.get(definition.id()))
                .map(state -> state == null ? (byte) 0x01 : switchByte(state.position()))
                .toList();
        }
        return switchStatesSupplier.get().stream()
            .sorted(Comparator.comparing(SwitchState::id))
            .map(state -> switchByte(state.position()))
            .toList();
    }

    private byte aspectByte(SignalAspect aspect) {
        return switch (aspect) {
            case RED -> 0x01;
            case YELLOW -> 0x02;
            case GREEN -> 0x04;
        };
    }

    private byte switchByte(SwitchPosition position) {
        return (byte) (position == SwitchPosition.REVERSE ? 0x02 : 0x01);
    }

    private int segmentNumber(String segmentId) {
        Map<String, OperationalLineData.TrackSegmentDefinition> byId = infrastructureCatalog.lineData().trackSegmentById();
        OperationalLineData.TrackSegmentDefinition definition = byId.get(segmentId);
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
