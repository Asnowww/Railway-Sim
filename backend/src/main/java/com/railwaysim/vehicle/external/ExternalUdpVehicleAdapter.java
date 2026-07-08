package com.railwaysim.vehicle.external;

import com.railwaysim.config.ExternalSimulatorProperties;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ExternalUdpVehicleAdapter implements ExternalVehicleSimulationAdapter {

    private final ExternalSimulatorProperties properties;
    private final ExternalSimulatorProperties.UdpProperties udpProperties;
    private final ExternalVehicleCommandMapper commandMapper;
    private final ExternalUdpPacketCodec packetCodec;
    private final LocalFallbackVehicleAdapter localFallback;
    private volatile ExternalSimulatorHealth health = ExternalSimulatorHealth.fallback(
        ExternalSimulatorMode.EXTERNAL_UDP,
        "NOT_STARTED"
    );

    public ExternalUdpVehicleAdapter(
        ExternalSimulatorProperties properties,
        ExternalVehicleCommandMapper commandMapper,
        ExternalUdpPacketCodec packetCodec,
        LocalFallbackVehicleAdapter localFallback
    ) {
        this.properties = properties;
        this.udpProperties = properties.getUdp();
        this.commandMapper = commandMapper;
        this.packetCodec = packetCodec;
        this.localFallback = localFallback;
    }

    @Override
    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }

        Instant startedAt = Instant.now();
        try (DatagramSocket socket = openSocket()) {
            socket.setSoTimeout((int) udpProperties.getTimeoutMillis());
            byte[] commandBytes = packetCodec.encodeCommands(commandMapper.toCommands(inputs), properties.getMaxTrains());
            DatagramPacket commandPacket = new DatagramPacket(
                commandBytes,
                commandBytes.length,
                InetAddress.getByName(udpProperties.getModelHost()),
                udpProperties.getModelPort()
            );
            socket.send(commandPacket);

            byte[] responseBytes = new byte[packetCodec.telemetryPacketBytes(properties.getMaxTrains())];
            DatagramPacket responsePacket = new DatagramPacket(responseBytes, responseBytes.length);
            socket.receive(responsePacket);

            List<ExternalTrainTelemetry> telemetry = packetCodec.decodeTelemetry(
                Arrays.copyOf(responsePacket.getData(), responsePacket.getLength()),
                properties.getMaxTrains()
            );
            health = new ExternalSimulatorHealth(
                ExternalSimulatorMode.EXTERNAL_UDP,
                "OK",
                Instant.now(),
                Duration.between(startedAt, Instant.now()).toMillis(),
                "GOOD"
            );
            return mergeWithLocalOutputs(inputs, telemetry);
        } catch (IOException | RuntimeException exception) {
            health = ExternalSimulatorHealth.fallback(ExternalSimulatorMode.EXTERNAL_UDP, exception.getClass().getSimpleName());
            throw new IllegalStateException("External UDP vehicle simulator step failed", exception);
        }
    }

    @Override
    public ExternalSimulatorHealth health() {
        return health;
    }

    private DatagramSocket openSocket() throws SocketException, IOException {
        String platformHost = udpProperties.getPlatformHost();
        if (platformHost == null || platformHost.isBlank() || "0.0.0.0".equals(platformHost)) {
            return new DatagramSocket(udpProperties.getPlatformPort());
        }
        return new DatagramSocket(new InetSocketAddress(InetAddress.getByName(platformHost), udpProperties.getPlatformPort()));
    }

    private List<VehiclePhysicsOutput> mergeWithLocalOutputs(
        List<VehiclePhysicsInput> inputs,
        List<ExternalTrainTelemetry> telemetry
    ) {
        Map<Integer, ExternalTrainTelemetry> telemetryByTrain = telemetry.stream()
            .collect(Collectors.toMap(ExternalTrainTelemetry::trainNo, Function.identity(), (left, right) -> right));
        Map<String, VehiclePhysicsOutput> localOutputByTrain = localFallback.stepFleet(inputs).stream()
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));

        return inputs.stream()
            .map(input -> {
                int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(input.trainId(), properties.getMaxTrains());
                ExternalTrainTelemetry trainTelemetry = telemetryByTrain.get(trainNo);
                if (trainTelemetry == null) {
                    throw new IllegalStateException("Missing UDP telemetry for train " + trainNo);
                }
                return ExternalVehicleOutputMerger.mergeTelemetry(localOutputByTrain.get(input.trainId()), trainTelemetry);
            })
            .toList();
    }
}
