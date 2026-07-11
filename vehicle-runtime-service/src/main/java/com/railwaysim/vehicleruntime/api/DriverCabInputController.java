package com.railwaysim.vehicleruntime.api;

import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcCodec;
import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicleruntime.model.DriverCommandAcceptance;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.runtime.DriverCommandHolder;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle/driver-cabs")
@CrossOrigin
public class DriverCabInputController {

    private final DriverCabPlcCodec codec = new DriverCabPlcCodec();
    private final DriverCommandHolder commandHolder;
    private final ConcurrentMap<String, Integer> sequenceNumbers = new ConcurrentHashMap<>();
    private final long commandTimeoutMs;

    public DriverCabInputController(
        DriverCommandHolder commandHolder,
        @Value("${railway.simulation.driver-command-timeout-ms:5000}") long commandTimeoutMs
    ) {
        this.commandHolder = commandHolder;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    @PostMapping(
        value = "/{trainId}/plc-input",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<?> applyPlcInput(
        @PathVariable String trainId,
        @RequestBody byte[] payload
    ) {
        DriverCabPlcInputPacket input;
        try {
            input = codec.decodeInput(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(
                DriverCommandAcceptance.rejected(trainId, "DECODE_FAILED", ex.getMessage()));
        }

        int seqNo = sequenceNumbers.merge(trainId, 1, (old, v) -> old + 1);
        Instant now = Instant.now();
        double traction = input.tractionNotchPercent() / 100.0;
        double brake = input.brakeNotchPercent() / 100.0;
        boolean eb = input.emergencyBrakeRequested();
        double direction = switch (input.directionHandleState()) {
            case FORWARD -> 1.0; case BACKWARD -> -1.0; default -> 0.0;
        };

        var cmd = new DriverControlCommandSnapshot(
            null, trainId, seqNo, now, now.plusMillis(commandTimeoutMs),
            traction, brake, eb, direction, input.openDoorRequested(),
            input.atoStartFlag(), "MANUAL", null
        );
        // Use reflection or a builder to set commandId — let's rebuild
        var accepted = new DriverControlCommandSnapshot(
            "PLC-" + trainId + "-" + seqNo, cmd.trainId(), cmd.sequenceNo(),
            cmd.receivedAt(), cmd.expiresAt(), cmd.tractionCommand(),
            cmd.brakeCommand(), cmd.emergencyBrake(), cmd.direction(),
            cmd.doorOpenRequest(), cmd.atoRequest(), cmd.operationMode(),
            cmd.traceId() != null ? cmd.traceId() : "trace-" + trainId + "-" + seqNo
        );

        commandHolder.store(accepted);
        return ResponseEntity.ok(DriverCommandAcceptance.accepted(accepted));
    }
}
