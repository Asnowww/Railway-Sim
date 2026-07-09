package com.railwaysim.vehicle.drivercab;

import com.railwaysim.signal.vehicle.SignalCabDisplayState;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import org.springframework.stereotype.Service;

@Service
public class DriverCabAdapter {

    private final TrainManager trainManager;
    private final DriverCabPlcCodec codec = new DriverCabPlcCodec();

    public DriverCabAdapter(TrainManager trainManager) {
        this.trainManager = trainManager;
    }

    public DriverCabPlcInputPacket decodePlcInput(byte[] payload) {
        return codec.decodeInput(payload);
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
