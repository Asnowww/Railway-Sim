package com.railwaysim.api;

import com.railwaysim.signal.vision.VisionUdpSendResponse;
import com.railwaysim.signal.vision.VisionUdpSender;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/signal/vision")
@CrossOrigin
public class SignalVisionController {

    private final VisionUdpSender visionUdpSender;

    public SignalVisionController(VisionUdpSender visionUdpSender) {
        this.visionUdpSender = visionUdpSender;
    }

    @PostMapping("/udp/send")
    public VisionUdpSendResponse sendVisionPacket(
        @RequestParam String trainId,
        @RequestParam(required = false) String host,
        @RequestParam(required = false) Integer port,
        @RequestParam(required = false) Integer localPort
    ) {
        return visionUdpSender.send(trainId, host, port, localPort);
    }
}
