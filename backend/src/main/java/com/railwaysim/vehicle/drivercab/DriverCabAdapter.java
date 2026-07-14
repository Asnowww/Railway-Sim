package com.railwaysim.vehicle.drivercab;

import com.railwaysim.signal.vehicle.SignalCabDisplayState;
import com.railwaysim.api.dto.DriverCabPlcGatewayRequest;
import com.railwaysim.config.DriverCabControlProperties;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DriverCabAdapter {

    private static final Logger log = LoggerFactory.getLogger(DriverCabAdapter.class);

    private final VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService;
    private final DriverCabPlcGatewayEncoder plcGatewayEncoder;
    private final TrainManager trainManager;
    private final DriverCabControlProperties controlProperties;

    public DriverCabAdapter(
        VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService,
        DriverCabPlcGatewayEncoder plcGatewayEncoder,
        TrainManager trainManager,
        DriverCabControlProperties controlProperties
    ) {
        this.vehicleRuntimeIntegrationService = vehicleRuntimeIntegrationService;
        this.plcGatewayEncoder = plcGatewayEncoder;
        this.trainManager = trainManager;
        this.controlProperties = controlProperties;
    }

    /** Browser-console structured input: always mirror for display, forward to 9300 only when FRONTEND is authoritative. */
    public Map<String, Object> encodeAndForwardPlcInput(String trainId, DriverCabPlcGatewayRequest input) {
        trainManager.applyDriverCabState(trainId, plcGatewayEncoder.toSnapshot(input, DriverCabControlSource.FRONTEND));
        if (!controlProperties.getControlSource().forwards(DriverCabControlSource.FRONTEND)) {
            return displayOnlyAcceptance(trainId, DriverCabControlSource.FRONTEND);
        }
        return vehicleRuntimeIntegrationService.forwardPlcInput(trainId, plcGatewayEncoder.encode(input));
    }

    /** Physical PLC raw 46-byte frame: always mirror for display, forward to 9300 only when PHYSICAL is authoritative. */
    public Map<String, Object> forwardPlcInput(String trainId, byte[] payload) {
        if (payload == null || payload.length != DriverCabPlcGatewayEncoder.PLC_INPUT_BYTES) {
            throw new IllegalArgumentException("driver cab PLC input must be exactly 46 bytes");
        }
        boolean known = trainManager.applyDriverCabState(
            trainId, plcGatewayEncoder.decodeSnapshot(payload, DriverCabControlSource.PHYSICAL)
        );
        if (!known) {
            log.debug("Driver cab PLC input for unknown central train {} kept as display mirror only", trainId);
        }
        if (!controlProperties.getControlSource().forwards(DriverCabControlSource.PHYSICAL)) {
            return displayOnlyAcceptance(trainId, DriverCabControlSource.PHYSICAL);
        }
        return vehicleRuntimeIntegrationService.forwardPlcInput(trainId, payload);
    }

    private Map<String, Object> displayOnlyAcceptance(String trainId, DriverCabControlSource source) {
        Map<String, Object> acceptance = new LinkedHashMap<>();
        acceptance.put("accepted", false);
        acceptance.put("trainId", trainId);
        acceptance.put("operationMode", "MANUAL");
        acceptance.put("decisionSource", "MIRROR");
        acceptance.put("reasonCode", "DISPLAY_ONLY_" + controlProperties.getControlSource().name());
        acceptance.put("inputSource", source.name());
        acceptance.put("controlSource", controlProperties.getControlSource().name());
        return acceptance;
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
