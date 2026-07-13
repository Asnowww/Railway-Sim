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

    public SignalVehicleInterfaceController(
        TrainManager trainManager,
        SignalService signalService,
        VisionVehicleStateStore visionVehicleStateStore
    ) {
        this.trainManager = trainManager;
        this.signalService = signalService;
        this.visionVehicleStateStore = visionVehicleStateStore;
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
    public List<VehicleSignalStatus> applyTelemetry(@RequestBody List<TrainOperationalTelemetry> telemetries) {
        throw new ResponseStatusException(
            HttpStatus.GONE,
            "central vehicle telemetry writes are retired; 9300 trainStates snapshots are authoritative"
        );
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
    public List<VehicleSignalStatus> applyContentPacket(
        @RequestParam int trainCount,
        @RequestBody byte[] payload
    ) {
        if (trainCount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trainCount must be positive");
        }
        if (payload == null || payload.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload is required");
        }
        throw new ResponseStatusException(
            HttpStatus.GONE,
            "central vehicle telemetry writes are retired; 9300 trainStates snapshots are authoritative"
        );
    }
}
