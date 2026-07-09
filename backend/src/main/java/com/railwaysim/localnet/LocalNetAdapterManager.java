package com.railwaysim.localnet;

import com.railwaysim.config.LocalNetProperties;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

@Service
public class LocalNetAdapterManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocalNetAdapterManager.class);

    private final LocalNetProperties properties;
    private final List<LocalNetAdapter> adapters;
    private final AtomicBoolean running = new AtomicBoolean();

    public LocalNetAdapterManager(LocalNetProperties properties, List<LocalNetAdapter> adapters) {
        this.properties = properties;
        this.adapters = adapters.stream()
            .sorted(Comparator.comparing(LocalNetAdapter::id))
            .toList();
    }

    public List<LocalNetHealth> health() {
        return adapters.stream().map(LocalNetAdapter::health).toList();
    }

    public Optional<LocalNetHealth> health(String adapterId) {
        return adapter(adapterId).map(LocalNetAdapter::health);
    }

    public Optional<LocalNetReplayResult> replay(String adapterId, byte[] payload) {
        return adapter(adapterId).map(adapter -> adapter.replay(payload));
    }

    @Override
    public void start() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        for (LocalNetAdapter adapter : adapters) {
            if (!adapter.enabled()) {
                continue;
            }
            try {
                adapter.start();
            } catch (RuntimeException ex) {
                log.warn("Failed to start localnet adapter {}: {}", adapter.id(), ex.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (LocalNetAdapter adapter : adapters) {
            try {
                adapter.stop();
            } catch (RuntimeException ex) {
                log.warn("Failed to stop localnet adapter {}: {}", adapter.id(), ex.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private Optional<LocalNetAdapter> adapter(String adapterId) {
        return adapters.stream()
            .filter(adapter -> adapter.id().equals(adapterId))
            .findFirst();
    }
}
