package com.railwaysim.localnet.powerpoints;

import com.railwaysim.config.LocalNetProperties;
import com.railwaysim.localnet.LocalNetAdapter;
import com.railwaysim.localnet.LocalNetHealth;
import com.railwaysim.localnet.LocalNetPacketRecord;
import com.railwaysim.localnet.LocalNetReplayResult;
import com.railwaysim.localnet.PacketDirection;
import com.railwaysim.localnet.ProtocolFamily;
import com.railwaysim.localnet.ProtocolPacketAuditService;
import com.railwaysim.localnet.RecentPacketStore;
import com.railwaysim.power.external.PowerNetworkOperationResult;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PowerPointAdapter implements LocalNetAdapter {

    private static final String ADAPTER_ID = "power-points";

    private final LocalNetProperties properties;
    private final PowerPointTableLoader tableLoader;
    private final PowerPointDomainMapper domainMapper;
    private final ProtocolPacketAuditService auditService;
    private final RestClient.Builder restClientBuilder;
    private final RecentPacketStore packetStore;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private List<PowerPointDefinition> definitions = List.of();
    private PowerPointDriver driver;
    private volatile PowerNetworkStateSnapshot latestSnapshot = new PowerNetworkStateSnapshot(
        Instant.now(),
        "DISABLED",
        "NO_DATA",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of()
    );

    public PowerPointAdapter(
        LocalNetProperties properties,
        PowerPointTableLoader tableLoader,
        PowerPointDomainMapper domainMapper,
        ProtocolPacketAuditService auditService,
        RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.tableLoader = tableLoader;
        this.domainMapper = domainMapper;
        this.auditService = auditService;
        this.restClientBuilder = restClientBuilder;
        this.packetStore = new RecentPacketStore(properties.getRecentPacketLimit());
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.POWER_POINTS;
    }

    @Override
    public boolean configured() {
        return properties.getPowerPoints().getPointTablePath() != null
            && !properties.getPowerPoints().getPointTablePath().isBlank();
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled() && properties.getPowerPoints().isEnabled();
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
        definitions = tableLoader.load(properties.getPowerPoints().getPointTablePath());
        driver = createDriver();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "localnet-power-points");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(
            this::poll,
            0,
            properties.getPowerPoints().getPollIntervalMillis(),
            TimeUnit.MILLISECONDS
        );
        record(PacketDirection.OUTBOUND, 0, "power point adapter started points=" + definitions.size(), "OK", "");
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

    public PowerNetworkStateSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    @Override
    public LocalNetReplayResult replay(byte[] payload) {
        try {
            String command = new String(payload, StandardCharsets.UTF_8).trim();
            String[] parts = command.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Power point replay payload must be pointId=value");
            }
            PowerPointDefinition definition = definition(parts[0])
                .orElseThrow(() -> new IllegalArgumentException("Unknown power point: " + parts[0]));
            PowerPointValue value = activeDriver().write(definition, parts[1]);
            String summary;
            if (definition.writePoint()) {
                PowerNetworkOperationResult result = domainMapper.applyWrite(definition, parts[1]);
                summary = "power point write " + value.pointId() + " result=" + result.resultState();
            } else {
                summary = "power point simulated value " + value.pointId() + "=" + value.value();
            }
            record(PacketDirection.REPLAY, payload.length, summary, "OK", "");
            return new LocalNetReplayResult(id(), family(), true, summary, "", Instant.now());
        } catch (RuntimeException ex) {
            record(PacketDirection.REPLAY, payload == null ? 0 : payload.length, "power point replay failed", "ERROR", ex.getMessage());
            return new LocalNetReplayResult(id(), family(), false, "power point replay failed", ex.getMessage(), Instant.now());
        }
    }

    private void poll() {
        try {
            List<PowerPointValue> values = activeDriver().snapshot(definitions);
            latestSnapshot = domainMapper.toSnapshot(definitions, values);
            record(PacketDirection.INBOUND, values.size(), "power point snapshot values=" + values.size(), "OK", "");
        } catch (RuntimeException ex) {
            record(PacketDirection.INBOUND, 0, "power point snapshot failed", "ERROR", ex.getMessage());
        }
    }

    private PowerPointDriver createDriver() {
        String driverName = properties.getPowerPoints().getDriver();
        if ("HTTP_SIM".equalsIgnoreCase(driverName)) {
            return new HttpSimPowerPointDriver(properties.getPowerPoints(), restClientBuilder);
        }
        return new LocalSimPowerPointDriver(definitions);
    }

    private PowerPointDriver activeDriver() {
        if (driver == null) {
            definitions = tableLoader.load(properties.getPowerPoints().getPointTablePath());
            driver = createDriver();
        }
        return driver;
    }

    private Optional<PowerPointDefinition> definition(String pointId) {
        if (definitions.isEmpty()) {
            definitions = tableLoader.load(properties.getPowerPoints().getPointTablePath());
        }
        return definitions.stream()
            .filter(definition -> definition.pointId().equals(pointId))
            .findFirst();
    }

    private void record(PacketDirection direction, int byteLength, String summary, String status, String error) {
        LocalNetPacketRecord record = packetStore.record(family(), id(), direction, byteLength, summary, status, error);
        auditService.record(record);
    }
}
