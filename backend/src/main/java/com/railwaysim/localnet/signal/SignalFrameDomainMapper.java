package com.railwaysim.localnet.signal;

import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommandCodec;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.protocol.SignalTrainContentCodec;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SignalFrameDomainMapper {

    private final TrainManager trainManager;
    private final SignalDatabaseNodeFrameCodec frameCodec = new SignalDatabaseNodeFrameCodec();
    private final SignalTrainLifecycleCommandCodec lifecycleCodec = new SignalTrainLifecycleCommandCodec();
    private final SignalTrainContentCodec trainContentCodec = new SignalTrainContentCodec();

    public SignalFrameDomainMapper(TrainManager trainManager) {
        this.trainManager = trainManager;
    }

    public SignalInboundResult applyInbound(byte[] payload) {
        if (isLifecyclePacket(payload)) {
            SignalTrainLifecycleCommand command = lifecycleCodec.decode(payload);
            trainManager.applyLifecycleCommand(command);
            return new SignalInboundResult(
                true,
                "signal lifecycle " + command.action() + " trains=" + command.trains().size()
            );
        }
        SignalDatabaseNodeFrame frame = frameCodec.decode(payload);
        String summary = frame.trainContentFrame()
            ? summarizeSignalToCentralTrainContent(frame.content())
            : "signal cab switch frame contentBytes=" + frame.content().length;
        return new SignalInboundResult(true, summary);
    }

    public byte[] encodeCentralTrainStateFrame(int maxTrains) {
        List<TrainOperationalTelemetry> telemetry = trainManager.states().stream()
            .limit(Math.max(1, maxTrains))
            .map(this::toTelemetry)
            .toList();
        return frameCodec.encode(frameCodec.centralToSignalTrainFrame(trainContentCodec.encode(telemetry)));
    }

    public SignalDatabaseNodeFrame decodeFrame(byte[] payload) {
        return frameCodec.decode(payload);
    }

    private TrainOperationalTelemetry toTelemetry(TrainState train) {
        return new TrainOperationalTelemetry(
            trainNo(train.id()),
            train.speedMetersPerSecond(),
            train.positionMeters(),
            direction(train.direction()),
            train.loadMassKg(),
            train.vehicleFaultSpeedLimitMetersPerSecond(),
            "EMERGENCY".equals(train.brakeState()),
            train.availableTractionCount(),
            train.availableBrakeCount()
        );
    }

    private int trainNo(String trainId) {
        if (trainId == null) {
            return 0;
        }
        int dash = trainId.lastIndexOf('-');
        String suffix = dash >= 0 ? trainId.substring(dash + 1) : trainId;
        try {
            return Math.max(1, Integer.parseInt(suffix));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private ExternalTrainDirection direction(String value) {
        if ("UP".equalsIgnoreCase(value)) {
            return ExternalTrainDirection.UP;
        }
        if ("DOWN".equalsIgnoreCase(value)) {
            return ExternalTrainDirection.DOWN;
        }
        return ExternalTrainDirection.UNKNOWN;
    }

    private boolean isLifecyclePacket(byte[] payload) {
        if (payload == null || payload.length < 3) {
            return false;
        }
        if (Byte.toUnsignedInt(payload[0]) != SignalTrainLifecycleCommandCodec.HEADER_1
            || Byte.toUnsignedInt(payload[1]) != SignalTrainLifecycleCommandCodec.HEADER_2) {
            return false;
        }
        int action = Byte.toUnsignedInt(payload[2]);
        for (SignalTrainLifecycleAction candidate : SignalTrainLifecycleAction.values()) {
            if (candidate.protocolCode() == action) {
                return true;
            }
        }
        return false;
    }

    private String summarizeSignalToCentralTrainContent(byte[] content) {
        ByteBuffer buffer = ByteBuffer.wrap(content == null ? new byte[0] : content).order(ByteOrder.LITTLE_ENDIAN);
        int switchCount = 0;
        int signalCount = 0;
        int trainCommandCount = 0;
        if (buffer.remaining() >= 2) {
            int switchBytes = Short.toUnsignedInt(buffer.getShort());
            int readableSwitchBytes = Math.min(switchBytes, buffer.remaining());
            switchCount = readableSwitchBytes / 3;
            buffer.position(buffer.position() + readableSwitchBytes);
        }
        if (buffer.remaining() >= 2) {
            int signalBytes = Short.toUnsignedInt(buffer.getShort());
            int readableSignalBytes = Math.min(signalBytes, buffer.remaining());
            signalCount = readableSignalBytes / 3;
            buffer.position(buffer.position() + readableSignalBytes);
        }
        if (buffer.remaining() >= 3) {
            trainCommandCount = buffer.remaining() / 3;
        }
        return "signal shadow content switches=" + switchCount
            + " signals=" + signalCount
            + " trainCommands=" + trainCommandCount;
    }

    public record SignalInboundResult(boolean accepted, String summary) {
    }
}
