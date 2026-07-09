package com.railwaysim.signal.vision;

import com.railwaysim.config.VisionUdpProperties;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class VisionUdpSender {

    private final VisionUdpProperties properties;
    private final VisionUdpPacketBuilder packetBuilder;

    public VisionUdpSender(VisionUdpProperties properties, VisionUdpPacketBuilder packetBuilder) {
        this.properties = properties;
        this.packetBuilder = packetBuilder;
    }

    public VisionUdpSendResponse send(String trainId, String host, Integer port, Integer localPort) {
        String targetHost = host == null || host.isBlank() ? properties.getTargetHost() : host.trim();
        int targetPort = port == null || port <= 0 ? properties.getTargetPort() : port;
        int sourcePort = localPort == null || localPort < 0 ? properties.getLocalPort() : localPort;
        VisionUdpPacket packet = packetBuilder.build(trainId);
        try (DatagramSocket socket = sourcePort > 0 ? new DatagramSocket(sourcePort) : new DatagramSocket()) {
            byte[] payload = packet.payload();
            DatagramPacket datagram = new DatagramPacket(
                payload,
                payload.length,
                InetAddress.getByName(targetHost),
                targetPort
            );
            socket.send(datagram);
            return new VisionUdpSendResponse(
                packet.trainId(),
                targetHost,
                targetPort,
                sourcePort,
                packet.counter(),
                payload.length,
                packet.signalCount(),
                packet.switchCount(),
                packet.otherTrainCount(),
                Instant.now()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send vision UDP packet", exception);
        }
    }
}
