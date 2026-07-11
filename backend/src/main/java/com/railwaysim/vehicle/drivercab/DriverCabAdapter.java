package com.railwaysim.vehicle.drivercab;

import com.railwaysim.signal.vehicle.SignalCabDisplayState;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.control.DriverCommandAcceptance;
import com.railwaysim.vehicle.control.DriverControlCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DriverCabAdapter {

    private final TrainManager trainManager;
    private final DriverCabPlcCodec codec = new DriverCabPlcCodec();
    private final ConcurrentMap<String, Integer> sequenceNumbers = new ConcurrentHashMap<>();
    private final long commandTimeoutMs;

    public DriverCabAdapter(
        TrainManager trainManager,
        @Value("${railway.simulation.driver-command-timeout-ms:5000}") long commandTimeoutMs
    ) {
        this.trainManager = trainManager;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public DriverCabPlcInputPacket decodePlcInput(byte[] payload) {
        return codec.decodeInput(payload);
    }

    public DriverCommandAcceptance applyAndAccept(String trainId, byte[] payload) {
        if (trainId == null || trainManager.state(trainId).isEmpty()) {
            return DriverCommandAcceptance.rejected(trainId, "UNKNOWN_TRAIN", "Train not found: " + trainId);
        }
        DriverCabPlcInputPacket input;
        try {
            input = codec.decodeInput(payload);
        } catch (IllegalArgumentException ex) {
            return DriverCommandAcceptance.rejected(trainId, "DECODE_FAILED", ex.getMessage());
        }
        int seqNo = sequenceNumbers.merge(trainId, 1, (old, v) -> old + 1);
        Instant now = Instant.now();
        double traction = input.masterHandleState() == DriverCabMasterHandleState.TRACTION
            ? input.tractionNotchPercent() / 100.0
            : 0.0;
        double brake = input.masterHandleState() == DriverCabMasterHandleState.BRAKE
            ? input.brakeNotchPercent() / 100.0
            : 0.0;
        boolean eb = input.emergencyBrakeRequested();
        double direction = switch (input.directionHandleState()) {
            case FORWARD -> 1.0; case BACKWARD -> -1.0; default -> 0.0;
        };
        var cmd = new DriverControlCommand(
            null, trainId, seqNo, now, now.plusMillis(commandTimeoutMs),
            traction, brake, eb, direction, input.openDoorRequested(),
            input.atoStartFlag(), input.atoStartFlag() ? "AUTO" : "MANUAL", null, payload
        );
        // Validate and apply the display/safety state before publishing the command
        // to the next-tick holder. A failed state update must never leave an
        // executable command cached behind a rejected HTTP response.
        trainManager.applyDriverCabInput(trainId, input);
        trainManager.storeDriverCommand(cmd);
        return DriverCommandAcceptance.accepted(cmd);
    }

    public TrainState applyPlcInput(String trainId, DriverCabPlcInputPacket input) {
        return trainManager.applyDriverCabInput(trainId, input);
    }

    public byte[] encodePlcOutput(TrainState train, SignalCabDisplayState display) {
        return codec.encodeOutput(toOutputPacket(train, display));
    }

    private DriverCabPlcOutputPacket toOutputPacket(TrainState train, SignalCabDisplayState display) {
        boolean signalOrPowerDetached = !"ATTACHED".equals(train.signalNetworkStatus())
            || !"ATTACHED".equals(train.powerNetworkStatus());
        return new DriverCabPlcOutputPacket(
            !"LOST".equals(train.currentCollectionStatus()) && "ATTACHED".equals(train.powerNetworkStatus()),
            !train.brakeAvailable(),
            !"CLOSED_LOCKED".equals(train.doorState()),
            "CLOSED_LOCKED".equals(train.doorState()),
            signalOrPowerDetached || "INVALID".equals(train.dataQuality()),
            display.turnbackInfo() != SignalCabDisplayState.TurnbackInfo.INACTIVE,
            display.maximumAvailableDrivingMode() == SignalCabDisplayState.DrivingMode.ATO
                || display.maximumAvailableDrivingMode() == SignalCabDisplayState.DrivingMode.DTO
                || display.maximumAvailableDrivingMode() == SignalCabDisplayState.DrivingMode.AR,
            false,
            display.currentDrivingMode() == SignalCabDisplayState.DrivingMode.ATO
                || display.currentDrivingMode() == SignalCabDisplayState.DrivingMode.DTO,
            display.turnbackInfo() == SignalCabDisplayState.TurnbackInfo.ACTIVE
        );
    }
}
