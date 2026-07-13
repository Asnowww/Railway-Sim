package com.railwaysim.vehicle.drivercab;

import com.railwaysim.signal.vehicle.SignalCabDisplayState;
import com.railwaysim.api.dto.DriverCabPlcGatewayRequest;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class DriverCabAdapter {

    private final VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService;
    private final DriverCabPlcGatewayEncoder plcGatewayEncoder;

    public DriverCabAdapter(
        VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService,
        DriverCabPlcGatewayEncoder plcGatewayEncoder
    ) {
        this.vehicleRuntimeIntegrationService = vehicleRuntimeIntegrationService;
        this.plcGatewayEncoder = plcGatewayEncoder;
    }

    public Map<String, Object> encodeAndForwardPlcInput(String trainId, DriverCabPlcGatewayRequest input) {
        return vehicleRuntimeIntegrationService.forwardPlcInput(trainId, plcGatewayEncoder.encode(input));
    }

    /** Forward a validated-size raw frame received from the physical PLC. */
    public Map<String, Object> forwardPlcInput(String trainId, byte[] payload) {
        if (payload == null || payload.length != DriverCabPlcGatewayEncoder.PLC_INPUT_BYTES) {
            throw new IllegalArgumentException("driver cab PLC input must be exactly 46 bytes");
        }
        return vehicleRuntimeIntegrationService.forwardPlcInput(trainId, payload);
    }

    public byte[] encodePlcOutput(TrainState train, SignalCabDisplayState display) {
        return new DriverCabPlcOutputPacket(
            !"LOST".equals(train.currentCollectionStatus()) && "ATTACHED".equals(train.powerNetworkStatus()),
            !train.brakeAvailable(),
            !"CLOSED_LOCKED".equals(train.doorState()),
            "CLOSED_LOCKED".equals(train.doorState()),
            (!"ATTACHED".equals(train.signalNetworkStatus())
                || !"ATTACHED".equals(train.powerNetworkStatus())
                || "INVALID".equals(train.dataQuality())),
            display.turnbackInfo() != SignalCabDisplayState.TurnbackInfo.INACTIVE,
            display.maximumAvailableDrivingMode() == SignalCabDisplayState.DrivingMode.ATO
                || display.maximumAvailableDrivingMode() == SignalCabDisplayState.DrivingMode.DTO
                || display.maximumAvailableDrivingMode() == SignalCabDisplayState.DrivingMode.AR,
            false,
            display.currentDrivingMode() == SignalCabDisplayState.DrivingMode.ATO
                || display.currentDrivingMode() == SignalCabDisplayState.DrivingMode.DTO,
            display.turnbackInfo() == SignalCabDisplayState.TurnbackInfo.ACTIVE
        ).toBytes();
    }
}
