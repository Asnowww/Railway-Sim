package com.railwaysim.signal.vehicle;

import com.railwaysim.vehicle.external.ExternalTrainDirection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class SignalTrainLifecycleCommandCodec {

    public static final int HEADER_1 = 0xff;
    public static final int HEADER_2 = 0xf1;
    private static final int ADD_BYTES_PER_TRAIN = 10;
    private static final int DELETE_BYTES_PER_TRAIN = 2;

    private final ByteOrder byteOrder;

    public SignalTrainLifecycleCommandCodec() {
        this(ByteOrder.LITTLE_ENDIAN);
    }

    public SignalTrainLifecycleCommandCodec(ByteOrder byteOrder) {
        this.byteOrder = byteOrder == null ? ByteOrder.LITTLE_ENDIAN : byteOrder;
    }

    public SignalTrainLifecycleCommand decode(byte[] payload) {
        if (payload.length < 3) {
            throw new IllegalArgumentException("Signal train lifecycle packet too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(byteOrder);
        validateHeader(unsignedByte(buffer.get()), unsignedByte(buffer.get()));
        SignalTrainLifecycleAction action = SignalTrainLifecycleAction.fromProtocolCode(unsignedByte(buffer.get()));
        if (action == SignalTrainLifecycleAction.CLEAR) {
            return SignalTrainLifecycleCommand.clear();
        }
        if (!buffer.hasRemaining()) {
            throw new IllegalArgumentException("Signal train lifecycle packet missing train count");
        }
        int trainCount = unsignedByte(buffer.get());
        int expectedBytes = 4 + trainCount * bytesPerTrain(action);
        if (payload.length < expectedBytes) {
            throw new IllegalArgumentException(
                "Signal train lifecycle packet too short: expected " + expectedBytes + " bytes, got " + payload.length
            );
        }
        List<SignalTrainLifecycleTrainSpec> trains = new ArrayList<>();
        for (int index = 0; index < trainCount; index++) {
            if (action == SignalTrainLifecycleAction.ADD) {
                int trainNo = unsignedShort(buffer.getShort());
                int linkId = unsignedShort(buffer.getShort());
                long offsetMeters = unsignedInt(buffer.getInt());
                ExternalTrainDirection direction = ExternalTrainDirection.fromProtocolCode(unsignedByte(buffer.get()));
                buffer.get(); // reserved
                trains.add(SignalTrainLifecycleTrainSpec.add(trainNo, linkId, offsetMeters, direction));
            } else {
                trains.add(SignalTrainLifecycleTrainSpec.delete(unsignedShort(buffer.getShort())));
            }
        }
        return new SignalTrainLifecycleCommand(action, trains);
    }

    public byte[] encode(SignalTrainLifecycleCommand command) {
        if (command.action() == SignalTrainLifecycleAction.CLEAR) {
            return new byte[] {
                (byte) HEADER_1,
                (byte) HEADER_2,
                (byte) command.action().protocolCode()
            };
        }
        ByteBuffer buffer = ByteBuffer
            .allocate(4 + command.trains().size() * bytesPerTrain(command.action()))
            .order(byteOrder);
        buffer.put((byte) HEADER_1);
        buffer.put((byte) HEADER_2);
        buffer.put((byte) command.action().protocolCode());
        buffer.put((byte) command.trains().size());
        for (SignalTrainLifecycleTrainSpec train : command.trains()) {
            if (command.action() == SignalTrainLifecycleAction.ADD) {
                buffer.putShort((short) train.trainNo());
                buffer.putShort((short) train.linkId());
                buffer.putInt((int) Math.round(train.offsetMeters()));
                buffer.put((byte) train.direction().protocolCode());
                buffer.put((byte) 0);
            } else {
                buffer.putShort((short) train.trainNo());
            }
        }
        return buffer.array();
    }

    private int bytesPerTrain(SignalTrainLifecycleAction action) {
        return action == SignalTrainLifecycleAction.ADD ? ADD_BYTES_PER_TRAIN : DELETE_BYTES_PER_TRAIN;
    }

    private void validateHeader(int first, int second) {
        if (first != HEADER_1 || second != HEADER_2) {
            throw new IllegalArgumentException("Invalid signal train lifecycle packet header");
        }
    }

    private int unsignedByte(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private int unsignedShort(short value) {
        return Short.toUnsignedInt(value);
    }

    private long unsignedInt(int value) {
        return Integer.toUnsignedLong(value);
    }
}
