package com.railwaysim.api;

import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vehicle.SignalVehicleCommand;
import com.railwaysim.signal.vehicle.VehicleSignalStatus;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.control.DriverCommandAcceptance;
import com.railwaysim.vehicle.drivercab.DriverCabAdapter;
import com.railwaysim.vehicle.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicle.drivercab.DriverCabStateSnapshot;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vehicle/driver-cabs")
@CrossOrigin
public class DriverCabController {

    private final TrainManager trainManager;
    private final SignalService signalService;
    private final DriverCabAdapter driverCabAdapter;

    public DriverCabController(
        TrainManager trainManager,
        SignalService signalService,
        DriverCabAdapter driverCabAdapter
    ) {
        this.trainManager = trainManager;
        this.signalService = signalService;
        this.driverCabAdapter = driverCabAdapter;
    }

    @PostMapping(
        value = "/{trainId}/plc-input",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<?> applyPlcInput(
        @PathVariable String trainId,
        @RequestBody byte[] payload
    ) {
        DriverCommandAcceptance acceptance = driverCabAdapter.applyAndAccept(trainId, payload);
        if (!acceptance.accepted()) {
            HttpStatus status = "UNKNOWN_TRAIN".equals(acceptance.reasonCode())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(acceptance);
        }
        return ResponseEntity.ok(acceptance);
    }

    @GetMapping("/{trainId}/state")
    public DriverCabStateSnapshot state(@PathVariable String trainId) {
        TrainState train = trainState(trainId);
        if (train.driverCabState() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver cab state not received for train: " + trainId);
        }
        return train.driverCabState();
    }

    @GetMapping(
        value = "/{trainId}/plc-output",
        produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<byte[]> plcOutput(@PathVariable String trainId) {
        TrainState train = trainState(trainId);
        SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(train, authorityByTrain().get(train.id()));
        return ResponseEntity.ok(driverCabAdapter.encodePlcOutput(train, command.cabDisplay()));
    }

    private DriverCabPlcInputPacket decodeInput(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload is required");
        }
        try {
            return driverCabAdapter.decodePlcInput(payload);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private TrainState trainState(String trainId) {
        return trainManager.state(trainId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Train not found: " + trainId));
    }

    private Map<String, MovementAuthority> authorityByTrain() {
        return signalService.authorities().stream()
            .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (left, right) -> right));
    }
}
