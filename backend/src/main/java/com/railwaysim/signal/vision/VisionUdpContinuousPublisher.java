package com.railwaysim.signal.vision;

import com.railwaysim.config.VisionUdpProperties;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class VisionUdpContinuousPublisher {

    private static final Logger log = LoggerFactory.getLogger(VisionUdpContinuousPublisher.class);

    private final VisionUdpProperties properties;
    private final VisionUdpSender sender;
    private final AtomicLong sentPackets = new AtomicLong();
    private final AtomicLong failedPackets = new AtomicLong();
    private volatile Instant lastSentAt;
    private volatile String lastError = "";

    public VisionUdpContinuousPublisher(VisionUdpProperties properties, VisionUdpSender sender) {
        this.properties = properties;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${railway.simulation.vision.interval-millis:100}")
    public void publish() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            VisionUdpSendResponse response = sender.send(properties.getTrainId(), null, null, null);
            sentPackets.incrementAndGet();
            lastSentAt = response.sentAt();
            lastError = "";
        } catch (RuntimeException exception) {
            long failures = failedPackets.incrementAndGet();
            lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (failures == 1 || failures % 100 == 0) {
                log.warn("Continuous vision UDP send failed (failure count={}): {}", failures, lastError);
            }
        }
    }

    public VisionUdpPublisherStatus status() {
        return new VisionUdpPublisherStatus(
            properties.isEnabled(),
            properties.getTrainId(),
            properties.getTargetHost(),
            properties.getTargetPort(),
            properties.getLocalPort(),
            properties.getIntervalMillis(),
            sentPackets.get(),
            failedPackets.get(),
            lastSentAt,
            lastError
        );
    }
}
