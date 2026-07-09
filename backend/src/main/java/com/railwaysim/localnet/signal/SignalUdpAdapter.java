package com.railwaysim.localnet.signal;

import com.railwaysim.config.LocalNetProperties;
import com.railwaysim.localnet.LocalNetAdapter;
import com.railwaysim.localnet.LocalNetHealth;
import com.railwaysim.localnet.LocalNetPacketRecord;
import com.railwaysim.localnet.LocalNetReplayResult;
import com.railwaysim.localnet.PacketDirection;
import com.railwaysim.localnet.ProtocolFamily;
import com.railwaysim.localnet.ProtocolPacketAuditService;
import com.railwaysim.localnet.RecentPacketStore;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SignalUdpAdapter implements LocalNetAdapter {

    private static final Logger log = LoggerFactory.getLogger(SignalUdpAdapter.class);
    private static final String ADAPTER_ID = "signal-udp";

    private final LocalNetProperties properties;
    private final SignalFrameDomainMapper mapper;
    private final ProtocolPacketAuditService auditService;
    private final RecentPacketStore packetStore;
    private final AtomicBoolean running = new AtomicBoolean();
    private DatagramSocket socket;
    private ScheduledExecutorService executor;

    public SignalUdpAdapter(
        LocalNetProperties properties,
        SignalFrameDomainMapper mapper,
        ProtocolPacketAuditService auditService
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.auditService = auditService;
        this.packetStore = new RecentPacketStore(properties.getRecentPacketLimit());
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.SIGNAL;
    }

    @Override
    public boolean configured() {
        return properties.getSignal().getBindPort() > 0 || properties.getSignal().getRemotePort() > 0;
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled() && properties.getSignal().isEnabled();
    }

    @Override
    public boolean running() {
        return running.get();
    }

    @Override
    public void start() {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalNetProperties.Signal signal = properties.getSignal();
            socket = new DatagramSocket(new InetSocketAddress(signal.getBindHost(), signal.getBindPort()));
            executor = Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "localnet-signal-udp");
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(this::receiveLoop);
            if (signal.getRemotePort() > 0) {
                executor.scheduleAtFixedRate(
                    this::sendCentralState,
                    signal.getSendIntervalMillis(),
                    signal.getSendIntervalMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
            record(PacketDirection.OUTBOUND, 0, "signal UDP adapter started", "OK", "");
        } catch (IOException ex) {
            running.set(false);
            record(PacketDirection.OUTBOUND, 0, "signal UDP adapter start failed", "ERROR", ex.getMessage());
            throw new IllegalStateException("Failed to start signal UDP adapter", ex);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (socket != null) {
            socket.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public LocalNetHealth health() {
        return packetStore.health(id(), family(), configured(), enabled(), running());
    }

    @Override
    public LocalNetReplayResult replay(byte[] payload) {
        try {
            SignalFrameDomainMapper.SignalInboundResult result = mapper.applyInbound(payload);
            record(PacketDirection.REPLAY, payload.length, result.summary(), "OK", "");
            return new LocalNetReplayResult(id(), family(), result.accepted(), result.summary(), "", Instant.now());
        } catch (RuntimeException ex) {
            record(PacketDirection.REPLAY, payload == null ? 0 : payload.length, "signal replay failed", "ERROR", ex.getMessage());
            return new LocalNetReplayResult(id(), family(), false, "signal replay failed", ex.getMessage(), Instant.now());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[4096];
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] payload = Arrays.copyOf(packet.getData(), packet.getLength());
                SignalFrameDomainMapper.SignalInboundResult result = mapper.applyInbound(payload);
                record(PacketDirection.INBOUND, payload.length, result.summary(), "OK", "");
            } catch (IOException ex) {
                if (running.get()) {
                    record(PacketDirection.INBOUND, 0, "signal UDP receive failed", "ERROR", ex.getMessage());
                }
            } catch (RuntimeException ex) {
                record(PacketDirection.INBOUND, 0, "signal UDP packet rejected", "ERROR", ex.getMessage());
            }
        }
    }

    private void sendCentralState() {
        try {
            LocalNetProperties.Signal signal = properties.getSignal();
            byte[] payload = mapper.encodeCentralTrainStateFrame(signal.getMaxTrains());
            DatagramPacket packet = new DatagramPacket(
                payload,
                payload.length,
                InetAddress.getByName(signal.getRemoteHost()),
                signal.getRemotePort()
            );
            socket.send(packet);
            record(PacketDirection.OUTBOUND, payload.length, "central train state frame trains<=%d".formatted(signal.getMaxTrains()), "OK", "");
        } catch (IOException | RuntimeException ex) {
            log.debug("Signal UDP outbound send failed", ex);
            record(PacketDirection.OUTBOUND, 0, "signal UDP send failed", "ERROR", ex.getMessage());
        }
    }

    private void record(PacketDirection direction, int byteLength, String summary, String status, String error) {
        LocalNetPacketRecord record = packetStore.record(family(), id(), direction, byteLength, summary, status, error);
        auditService.record(record);
    }
}
