package com.railwaysim.api;

import com.railwaysim.signal.vision.VisionUdpContinuousPublisher;
import com.railwaysim.signal.vision.VisionUdpPublisherStatus;
import com.railwaysim.signal.vision.VisionUdpDecodedPacket;
import com.railwaysim.signal.vision.VisionUdpPacketDecoder;
import com.railwaysim.signal.vision.VisionUdpSendResponse;
import com.railwaysim.signal.vision.VisionUdpSender;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/signal/vision")
@CrossOrigin
public class SignalVisionController {

    private final VisionUdpSender visionUdpSender;
    private final VisionUdpContinuousPublisher continuousPublisher;
    private final VisionUdpPacketDecoder packetDecoder;

    public SignalVisionController(
        VisionUdpSender visionUdpSender,
        VisionUdpContinuousPublisher continuousPublisher,
        VisionUdpPacketDecoder packetDecoder
    ) {
        this.visionUdpSender = visionUdpSender;
        this.continuousPublisher = continuousPublisher;
        this.packetDecoder = packetDecoder;
    }

    @GetMapping("/udp/status")
    public VisionUdpPublisherStatus visionPublisherStatus() {
        return continuousPublisher.status();
    }

    @PostMapping(value = "/udp/decode", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public VisionUdpDecodedPacket decodeVisionPacket(@RequestBody byte[] payload) {
        return packetDecoder.decode(payload);
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
