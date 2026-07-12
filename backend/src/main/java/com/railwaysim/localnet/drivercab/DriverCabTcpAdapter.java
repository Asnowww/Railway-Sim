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
import com.railwaysim.vehicle.drivercab.DriverCabPlcCodec;
import com.railwaysim.vehicle.control.DriverCommandAcceptance;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
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
            if (payload.length == DriverCabPlcCodec.PLC_TO_UPPER_BYTES) {
                DriverCommandAcceptance acceptance = driverCabAdapter.applyAndAccept("TR-001", payload);
                if (!acceptance.accepted()) {
                    throw new IllegalArgumentException("PLC replay rejected: " + acceptance.reasonCode());
                }
                String summary = "driver cab PLC replay forwarded train=" + acceptance.trainId();
                record(PacketDirection.REPLAY, payload.length, summary, "OK", "");
                return new LocalNetReplayResult(id(), family(), true, summary, "", Instant.now());
            }
            if (screenCodec.isNetworkScreenPacket(payload)) {
                String summary = "driver cab network screen replay bytes=" + payload.length;
                record(PacketDirection.REPLAY, payload.length, summary, "OK", "");
                return new LocalNetReplayResult(id(), family(), true, summary, "", Instant.now());
            }
            if (payload.length == DriverCabScreenPacketCodec.NETWORK_SCREEN_INPUT_BYTES) {
                int mask = screenCodec.decodeTractionCutMask(payload);
                driverCabAdapter.forwardTractionCut("TR-001", payload);
                String summary = "driver cab network screen traction-cut mask=" + mask;
                record(PacketDirection.REPLAY, payload.length, summary, "OK", "");
                return new LocalNetReplayResult(id(), family(), true, summary, "", Instant.now());
            }
            if (screenCodec.isSignalScreenPacket(payload)) {
                String summary = "driver cab signal screen replay bytes=" + payload.length;
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
        byte[] buffer = new byte[DriverCabPlcCodec.PLC_TO_UPPER_BYTES];
        int offset = 0;
        while (running.get() && !socket.isClosed()) {
            try {
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
                if (offset == DriverCabPlcCodec.PLC_TO_UPPER_BYTES) {
                    DriverCommandAcceptance acceptance = driverCabAdapter.applyAndAccept(config.trainId(), buffer.clone());
                    if (!acceptance.accepted()) {
                        throw new IllegalArgumentException("PLC input rejected: " + acceptance.reasonCode());
                    }
                    record(PacketDirection.INBOUND, offset,
                        "PLC input forwarded train=" + acceptance.trainId(), "OK", "");
                    offset = 0;
                }
            } catch (SocketTimeoutException ignored) {
                // Keep any partial frame in buffer; periodic write still happens below.
            }
            Optional<byte[]> response = plcOutput(config.trainId());
            if (response.isPresent()) {
                output.write(response.get());
                output.flush();
                record(PacketDirection.OUTBOUND, response.get().length, "PLC output train=" + config.trainId(), "OK", "");
            }
        }
    }

    private void handleScreen(DriverCabConnectionConfig config, Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        byte[] inputFrame = config.role() == DriverCabRole.NETWORK_SCREEN
            ? new byte[DriverCabScreenPacketCodec.NETWORK_SCREEN_INPUT_BYTES]
            : null;
        int inputOffset = 0;
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
            if (inputFrame != null && input.available() > 0) {
                try {
                    int read = input.read(inputFrame, inputOffset, inputFrame.length - inputOffset);
                    if (read < 0) {
                        break;
                    }
                    inputOffset += read;
                    if (inputOffset == inputFrame.length) {
                        int mask = screenCodec.decodeTractionCutMask(inputFrame);
                        driverCabAdapter.forwardTractionCut(config.trainId(), inputFrame.clone());
                        record(PacketDirection.INBOUND, inputFrame.length,
                            "NETWORK_SCREEN traction-cut train=" + config.trainId() + " mask=" + mask, "OK", "");
                        inputOffset = 0;
                    }
                } catch (SocketTimeoutException ignored) { }
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
