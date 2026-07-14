package com.railwaysim.localnet.drivercab;

import com.railwaysim.config.LocalNetProperties;
import com.railwaysim.localnet.LocalNetAdapter;
import com.railwaysim.localnet.LocalNetHealth;
import com.railwaysim.localnet.LocalNetPacketRecord;
import com.railwaysim.localnet.LocalNetReplayResult;
import com.railwaysim.localnet.PacketDirection;
import com.railwaysim.localnet.ProtocolFamily;
import com.railwaysim.localnet.ProtocolPacketAuditService;
import com.railwaysim.localnet.RecentPacketStore;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vehicle.SignalVehicleCommand;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.drivercab.DriverCabAdapter;
import com.railwaysim.vehicle.drivercab.DriverCabPlcGatewayEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DriverCabTcpAdapter implements LocalNetAdapter {

    private static final Logger log = LoggerFactory.getLogger(DriverCabTcpAdapter.class);
    private static final String ADAPTER_ID = "driver-cab-tcp";
    private static final byte[] PLC_IDENTIFY = {0x55, (byte) 0xaa, 0x55, (byte) 0xaa};

    private final LocalNetProperties properties;
    private final DriverCabAdapter driverCabAdapter;
    private final TrainManager trainManager;
    private final SignalService signalService;
    private final PowerService powerService;
    private final ProtocolPacketAuditService auditService;
    private final RecentPacketStore packetStore;
    private final DriverCabScreenPacketCodec screenCodec = new DriverCabScreenPacketCodec();
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService executor;

    public DriverCabTcpAdapter(
        LocalNetProperties properties,
        DriverCabAdapter driverCabAdapter,
        TrainManager trainManager,
        SignalService signalService,
        PowerService powerService,
        ProtocolPacketAuditService auditService
    ) {
        this.properties = properties;
        this.driverCabAdapter = driverCabAdapter;
        this.trainManager = trainManager;
        this.signalService = signalService;
        this.powerService = powerService;
        this.auditService = auditService;
        this.packetStore = new RecentPacketStore(properties.getRecentPacketLimit());
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.DRIVER_CAB;
    }

    @Override
    public boolean configured() {
        return connections().stream().anyMatch(DriverCabConnectionConfig::configured);
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled() && properties.getDriverCab().isEnabled();
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
        List<DriverCabConnectionConfig> configs = connections().stream()
            .filter(DriverCabConnectionConfig::configured)
            .toList();
        executor = Executors.newFixedThreadPool(Math.max(1, configs.size()), runnable -> {
            Thread thread = new Thread(runnable, "localnet-driver-cab-tcp");
            thread.setDaemon(true);
            return thread;
        });
        for (DriverCabConnectionConfig config : configs) {
            executor.execute(() -> connectionLoop(config));
        }
        record(PacketDirection.OUTBOUND, 0, "driver cab TCP adapter started connections=" + configs.size(), "OK", "");
    }

    @Override
    public void stop() {
        running.set(false);
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
            if (screenCodec.isNetworkScreenPacket(payload)) {
                DriverCabScreenPacketCodec.NetworkScreenFrame decoded = screenCodec.decodeNetworkScreen(payload);
                String summary = "driver cab network screen replay bytes=" + payload.length
                    + " train=" + decoded.trainNumber();
                record(PacketDirection.REPLAY, payload.length, summary, "OK", "");
                return new LocalNetReplayResult(id(), family(), true, summary, "", Instant.now());
            }
            if (screenCodec.isSignalScreenPacket(payload)) {
                DriverCabScreenPacketCodec.SignalScreenFrame decoded = screenCodec.decodeSignalScreen(payload);
                String summary = "driver cab signal screen replay bytes=" + payload.length
                    + " train=" + decoded.trainNumber();
                record(PacketDirection.REPLAY, payload.length, summary, "OK", "");
                return new LocalNetReplayResult(id(), family(), true, summary, "", Instant.now());
            }
            throw new IllegalArgumentException("Unsupported driver cab replay packet length: " + payload.length);
        } catch (RuntimeException ex) {
            record(PacketDirection.REPLAY, payload == null ? 0 : payload.length, "driver cab replay failed", "ERROR", ex.getMessage());
            return new LocalNetReplayResult(id(), family(), false, "driver cab replay failed", ex.getMessage(), Instant.now());
        }
    }

    private void connectionLoop(DriverCabConnectionConfig config) {
        while (running.get()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(config.host(), config.port()), (int) config.timeoutMillis());
                socket.setSoTimeout((int) config.timeoutMillis());
                record(PacketDirection.OUTBOUND, 0, "driver cab connected " + config.role() + " " + config.trainId(), "OK", "");
                if (config.role() == DriverCabRole.PLC) {
                    handlePlc(config, socket);
                } else {
                    handleScreen(config, socket);
                }
            } catch (IOException | RuntimeException ex) {
                if (running.get()) {
                    log.debug("Driver cab connection failed: {}", config, ex);
                    record(PacketDirection.INBOUND, 0, "driver cab connection failed " + config.role(), "ERROR", ex.getMessage());
                    sleep(properties.getDriverCab().getReconnectMillis());
                }
            }
        }
    }

    private void handlePlc(DriverCabConnectionConfig config, Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        byte[] readBuffer = new byte[512];
        byte[] pending = new byte[0];
        byte[] lastOutput = null;
        while (running.get() && !socket.isClosed()) {
            try {
                int read = input.read(readBuffer);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    byte[] combined = Arrays.copyOf(pending, pending.length + read);
                    System.arraycopy(readBuffer, 0, combined, pending.length, read);
                    pending = drainPlcFrames(config, combined);
                }
            } catch (SocketTimeoutException ignored) {
                // Keep partial TCP bytes for the next read.
            }
            lastOutput = writePlcOutputOnChange(config, output, lastOutput);
        }
    }

    /**
     * Extract every complete 46-byte PLC frame from the accumulated stream, re-synchronising on the
     * {@code 55 AA 55 AA} identify so a mid-stream reconnect or partial packet never poisons framing.
     * Returns the unconsumed tail (possibly a partial identify prefix) to carry into the next read.
     */
    private byte[] drainPlcFrames(DriverCabConnectionConfig config, byte[] pending) {
        int frameLength = DriverCabPlcGatewayEncoder.PLC_INPUT_BYTES;
        byte[] buffer = pending;
        while (true) {
            int identifyAt = indexOfIdentify(buffer);
            if (identifyAt < 0) {
                return trailingIdentifyPrefix(buffer);
            }
            if (identifyAt > 0) {
                buffer = Arrays.copyOfRange(buffer, identifyAt, buffer.length);
            }
            if (buffer.length < frameLength) {
                return buffer;
            }
            byte[] frame = Arrays.copyOfRange(buffer, 0, frameLength);
            buffer = Arrays.copyOfRange(buffer, frameLength, buffer.length);
            forwardPlcFrame(config, frame);
        }
    }

    private void forwardPlcFrame(DriverCabConnectionConfig config, byte[] frame) {
        try {
            Map<String, Object> acceptance = driverCabAdapter.forwardPlcInput(config.trainId(), frame);
            String reason = String.valueOf(acceptance.getOrDefault("reasonCode", ""));
            if (!Boolean.FALSE.equals(acceptance.get("accepted"))) {
                record(PacketDirection.INBOUND, frame.length, "PLC input forwarded train=" + config.trainId(), "OK", "");
            } else if (reason.startsWith("DISPLAY_ONLY")) {
                record(PacketDirection.INBOUND, frame.length,
                    "PLC input mirrored (display-only) train=" + config.trainId(), "OK", reason);
            } else {
                // A single rejected frame must not tear down the persistent PLC link.
                record(PacketDirection.INBOUND, frame.length,
                    "PLC input rejected train=" + config.trainId(), "WARN", reason);
            }
        } catch (RuntimeException ex) {
            record(PacketDirection.INBOUND, frame.length,
                "PLC input error train=" + config.trainId(), "ERROR", ex.getMessage());
        }
    }

    /**
     * Send the 26-byte PLC output frame only when its indicator data region (bytes 24-25) changes.
     * The protocol declares no fixed output period for the upper machine -> PLC direction; the header
     * timestamp is refreshed each time we actually transmit, so it is excluded from change detection.
     */
    private byte[] writePlcOutputOnChange(DriverCabConnectionConfig config, OutputStream output, byte[] lastOutput)
        throws IOException {
        Optional<byte[]> response = plcOutput(config.trainId());
        if (response.isEmpty()) {
            return lastOutput;
        }
        byte[] frame = response.get();
        if (sameIndicatorRegion(frame, lastOutput)) {
            return lastOutput;
        }
        output.write(frame);
        output.flush();
        record(PacketDirection.OUTBOUND, frame.length, "PLC output train=" + config.trainId(), "OK", "");
        return frame;
    }

    private boolean sameIndicatorRegion(byte[] current, byte[] previous) {
        if (current == null || previous == null || current.length < 26 || previous.length < 26) {
            return false;
        }
        return current[24] == previous[24] && current[25] == previous[25];
    }

    private int indexOfIdentify(byte[] buffer) {
        for (int index = 0; index + PLC_IDENTIFY.length <= buffer.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < PLC_IDENTIFY.length; offset++) {
                if (buffer[index + offset] != PLC_IDENTIFY[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        return -1;
    }

    private byte[] trailingIdentifyPrefix(byte[] buffer) {
        int keep = Math.min(PLC_IDENTIFY.length - 1, buffer.length);
        for (int prefix = keep; prefix > 0; prefix--) {
            boolean match = true;
            for (int offset = 0; offset < prefix; offset++) {
                if (buffer[buffer.length - prefix + offset] != PLC_IDENTIFY[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return Arrays.copyOfRange(buffer, buffer.length - prefix, buffer.length);
            }
        }
        return new byte[0];
    }

    private void handleScreen(DriverCabConnectionConfig config, Socket socket) throws IOException {
        OutputStream output = socket.getOutputStream();
        while (running.get() && !socket.isClosed()) {
            Optional<TrainState> train = trainManager.state(config.trainId());
            if (train.isPresent()) {
                SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(train.get(), authorityByTrain().get(train.get().id()));
                byte[] payload = config.role() == DriverCabRole.NETWORK_SCREEN
                    ? screenCodec.encodeNetworkScreen(train.get(), command.cabDisplay(), powerService.states())
                    : screenCodec.encodeSignalScreen(train.get(), command.cabDisplay());
                output.write(payload);
                output.flush();
                record(PacketDirection.OUTBOUND, payload.length, config.role() + " output train=" + config.trainId(), "OK", "");
            }
            sleep(config.cycleMillis());
        }
    }

    private Optional<byte[]> plcOutput(String trainId) {
        return trainManager.state(trainId).map(train -> {
            SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(train, authorityByTrain().get(train.id()));
            return driverCabAdapter.encodePlcOutput(train, command.cabDisplay());
        });
    }

    private Map<String, MovementAuthority> authorityByTrain() {
        return signalService.authorities().stream()
            .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (left, right) -> right));
    }

    private List<DriverCabConnectionConfig> connections() {
        List<DriverCabConnectionConfig> configs = new ArrayList<>();
        for (LocalNetProperties.DriverCabConnection connection : properties.getDriverCab().getConnections()) {
            configs.add(DriverCabConnectionConfig.from(connection));
        }
        return configs;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(PacketDirection direction, int byteLength, String summary, String status, String error) {
        LocalNetPacketRecord record = packetStore.record(family(), id(), direction, byteLength, summary, status, error);
        auditService.record(record);
    }
}
