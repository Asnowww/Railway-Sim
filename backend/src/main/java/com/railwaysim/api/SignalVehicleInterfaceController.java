package com.railwaysim.api;

import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vehicle.SignalVehicleCommand;
import com.railwaysim.signal.vehicle.VehicleSignalStatus;
import com.railwaysim.signal.vision.VisionVehicleState;
import com.railwaysim.signal.vision.VisionVehicleStateRequest;
import com.railwaysim.signal.vision.VisionVehicleStateStore;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import com.railwaysim.vehicle.protocol.SignalTrainContentCodec;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryGatewayService;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/signal/vehicles")
@CrossOrigin
public class SignalVehicleInterfaceController {

    private final TrainManager trainManager;
    private final SignalService signalService;
    private final VisionVehicleStateStore visionVehicleStateStore;
    private final VehicleTelemetryGatewayService telemetryGatewayService;
    private final SignalTrainContentCodec trainContentCodec = new SignalTrainContentCodec();

    public SignalVehicleInterfaceController(
        TrainManager trainManager,
        SignalService signalService,
        VisionVehicleStateStore visionVehicleStateStore,
        VehicleTelemetryGatewayService telemetryGatewayService
    ) {
        this.trainManager = trainManager;
        this.signalService = signalService;
        this.visionVehicleStateStore = visionVehicleStateStore;
        this.telemetryGatewayService = telemetryGatewayService;
    }

    @GetMapping("/statuses")
    public List<VehicleSignalStatus> statuses() {
        return trainManager.states().stream()
            .map(VehicleSignalStatus::from)
            .toList();
    }

    @GetMapping("/commands")
    public List<SignalVehicleCommand> commands() {
        Map<String, MovementAuthority> authorityByTrain = signalService.authorities().stream()
            .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (left, right) -> right));
        return trainManager.states().stream()
            .map(train -> SignalVehicleCommand.fromAuthority(train, authorityByTrain.get(train.id())))
            .toList();
    }

    @PostMapping("/telemetry")
    public VehicleTelemetryResponse applyTelemetry(@RequestBody List<TrainOperationalTelemetry> telemetries) {
        return telemetryGatewayService.forward("SIGNAL_HTTP_JSON", telemetries);
    }

    @PutMapping("/{trainId}/vision-state")
    public VisionVehicleState putVisionState(
        @PathVariable String trainId,
        @RequestBody VisionVehicleStateRequest request
    ) {
        return visionVehicleStateStore.put(trainId, request);
    }

    @GetMapping("/vision-states")
    public List<VisionVehicleState> visionStates() {
        return visionVehicleStateStore.states();
    }

    @PostMapping(
        value = "/telemetry/content-packet",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public VehicleTelemetryResponse applyContentPacket(
        @RequestParam int trainCount,
        @RequestBody byte[] payload
    ) {
        if (trainCount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trainCount must be positive");
        }
        if (payload == null || payload.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload is required");
        }
        try {
            return telemetryGatewayService.forward(
                "SIGNAL_HTTP_BINARY", trainContentCodec.decode(payload, trainCount)
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
