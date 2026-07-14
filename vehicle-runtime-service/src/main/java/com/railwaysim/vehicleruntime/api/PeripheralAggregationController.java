package com.railwaysim.vehicleruntime.api;

import com.railwaysim.vehicleruntime.protocol.PeripheralChannel;
import com.railwaysim.vehicleruntime.protocol.PeripheralFrame;
import com.railwaysim.vehicleruntime.protocol.PeripheralFrameCodec;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Single 9300 ingress for native peripheral frames forwarded by the central gateway. */
@RestController
@RequestMapping("/vehicle-runtime/peripherals")
public class PeripheralAggregationController {

    private final PeripheralFrameCodec codec = new PeripheralFrameCodec();
    private final DriverCabInputController driverCabInputController;
    private final AtomicLong acceptedFrames = new AtomicLong();
    private final AtomicLong rejectedFrames = new AtomicLong();
    private volatile int lastSequence;
    private volatile String lastTrainId = "";

    public PeripheralAggregationController(DriverCabInputController driverCabInputController) {
        this.driverCabInputController = driverCabInputController;
    }

    @PostMapping(
        value = "/frame",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> accept(@RequestBody byte[] bytes) {
        final PeripheralFrame frame;
        try {
            frame = codec.decode(bytes);
        } catch (IllegalArgumentException exception) {
            rejectedFrames.incrementAndGet();
            return ResponseEntity.badRequest().body(Map.of(
                "accepted", false,
                "reasonCode", "AGGREGATE_DECODE_FAILED",
                "message", exception.getMessage()
            ));
        }
        if (frame.channel() != PeripheralChannel.PLC_INPUT) {
            rejectedFrames.incrementAndGet();
            return ResponseEntity.badRequest().body(Map.of(
                "accepted", false,
                "reasonCode", "CHANNEL_DIRECTION_INVALID",
                "message", "Only PLC_INPUT is valid on the vehicle-runtime ingress"
            ));
        }
        ResponseEntity<?> response = driverCabInputController.applyPlcInput(frame.trainId(), frame.payload());
        if (response.getStatusCode().is2xxSuccessful()) {
            acceptedFrames.incrementAndGet();
            lastSequence = frame.sequence();
            lastTrainId = frame.trainId();
        } else {
            rejectedFrames.incrementAndGet();
        }
        return response;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "protocolVersion", PeripheralFrameCodec.VERSION,
            "port", 9300,
            "acceptedChannels", new String[] {PeripheralChannel.PLC_INPUT.name()},
            "acceptedFrames", acceptedFrames.get(),
            "rejectedFrames", rejectedFrames.get(),
            "lastSequence", lastSequence,
            "lastTrainId", lastTrainId
        );
    }
}
