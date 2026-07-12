package com.railwaysim.vehicleruntime.api;

import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcCodec;
import com.railwaysim.vehicleruntime.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicleruntime.model.DriverCommandAcceptance;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.runtime.DriverCommandHolder;
import com.railwaysim.vehicleruntime.runtime.VehicleRuntimeManager;
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
    private static final byte[] IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};
    private final DriverCommandHolder commandHolder;
    private final VehicleRuntimeManager runtimeManager;
    private final ConcurrentMap<String, Integer> sequenceNumbers = new ConcurrentHashMap<>();
    private final long commandTimeoutMs;

    public DriverCabInputController(
        DriverCommandHolder commandHolder,
        VehicleRuntimeManager runtimeManager,
        @Value("${railway.simulation.driver-command-timeout-ms:5000}") long commandTimeoutMs
    ) {
        this.commandHolder = commandHolder;
        this.runtimeManager = runtimeManager;
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
        if (!runtimeManager.hasInstance(trainId)) {
            return ResponseEntity.status(404).body(
                DriverCommandAcceptance.rejected(trainId, "UNKNOWN_TRAIN", "Vehicle runtime instance not found"));
        }
        DriverCabPlcInputPacket input;
        try {
            input = codec.decodeInput(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(
                DriverCommandAcceptance.rejected(trainId, "DECODE_FAILED", ex.getMessage()));
        }

        int seqNo = sequenceNumbers.merge(trainId, 1, (old, v) -> old + 1);
        Instant now = Instant.now();
        double traction = input.masterHandleState()
            == com.railwaysim.vehicleruntime.drivercab.DriverCabMasterHandleState.TRACTION
            ? input.tractionNotchPercent() / 100.0 : 0.0;
        double brake = input.masterHandleState()
            == com.railwaysim.vehicleruntime.drivercab.DriverCabMasterHandleState.BRAKE
            ? input.brakeNotchPercent() / 100.0 : 0.0;
        boolean eb = input.emergencyBrakeRequested();
        double direction = switch (input.directionHandleState()) {
            case FORWARD -> 1.0; case BACKWARD -> -1.0; default -> 0.0;
        };

        var cmd = new DriverControlCommandSnapshot(
            null, trainId, seqNo, now, now.plusMillis(commandTimeoutMs),
            traction, brake, eb, direction, input.openDoorRequested(),
            input.atoStartFlag(), "MANUAL", null
        );
        var accepted = new DriverControlCommandSnapshot(
            "PLC-" + trainId + "-" + seqNo, cmd.trainId(), cmd.sequenceNo(),
            cmd.receivedAt(), cmd.expiresAt(), cmd.tractionCommand(),
            cmd.brakeCommand(), cmd.emergencyBrake(), cmd.direction(),
            cmd.doorOpenRequest(), cmd.atoRequest(), cmd.operationMode(),
            cmd.traceId() != null ? cmd.traceId() : "trace-" + trainId + "-" + seqNo
        );

        commandHolder.store(accepted);
        runtimeManager.applyDriverCabInput(trainId, input);
        return ResponseEntity.ok(DriverCommandAcceptance.accepted(accepted));
    }

    @PostMapping(
        value = "/{trainId}/traction-cut",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<?> applyTractionCut(
        @PathVariable String trainId,
        @RequestBody byte[] payload
    ) {
        if (!runtimeManager.hasInstance(trainId)) {
            return ResponseEntity.notFound().build();
        }
        try {
            validateNetworkScreenInput(payload);
            int cutMask = Byte.toUnsignedInt(payload[24]) & 0x3f;
            runtimeManager.applyTractionCut(trainId, cutMask != 0);
            return ResponseEntity.ok().body(java.util.Map.of(
                "trainId", trainId, "tractionCutMask", cutMask, "tractionCut", cutMask != 0));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    private void validateNetworkScreenInput(byte[] payload) {
        if (payload == null || payload.length != 26) {
            throw new IllegalArgumentException("network screen input length is invalid: expected 26 bytes");
        }
        for (int i = 0; i < IDENTIFY.length; i++) {
            if (payload[i] != IDENTIFY[i]) {
                throw new IllegalArgumentException("network screen input identify bytes are invalid");
            }
        }
        var header = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (Short.toUnsignedInt(header.getShort(4)) != 26 || Short.toUnsignedInt(header.getShort(6)) != 2) {
            throw new IllegalArgumentException("network screen input length fields are invalid");
        }
    }
}
