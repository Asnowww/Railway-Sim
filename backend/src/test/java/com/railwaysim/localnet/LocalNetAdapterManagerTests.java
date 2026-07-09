package com.railwaysim.localnet;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.LocalNetProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalNetAdapterManagerTests {

    @Test
    void doesNotStartAdaptersWhenGlobalLocalnetIsDisabled() {
        LocalNetProperties properties = new LocalNetProperties();
        FakeAdapter adapter = new FakeAdapter(true);
        LocalNetAdapterManager manager = new LocalNetAdapterManager(properties, List.of(adapter));

        manager.start();

        assertThat(manager.isRunning()).isFalse();
        assertThat(adapter.started).isFalse();
    }

    @Test
    void startsOnlyEnabledAdaptersWhenGlobalLocalnetIsEnabled() {
        LocalNetProperties properties = new LocalNetProperties();
        properties.setEnabled(true);
        FakeAdapter enabled = new FakeAdapter(true);
        FakeAdapter disabled = new FakeAdapter(false);
        LocalNetAdapterManager manager = new LocalNetAdapterManager(properties, List.of(enabled, disabled));

        manager.start();

        assertThat(manager.isRunning()).isTrue();
        assertThat(enabled.started).isTrue();
        assertThat(disabled.started).isFalse();
        manager.stop();
    }

    private static class FakeAdapter implements LocalNetAdapter {

        private final boolean enabled;
        private boolean started;

        private FakeAdapter(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String id() {
            return enabled ? "enabled" : "disabled";
        }

        @Override
        public ProtocolFamily family() {
            return ProtocolFamily.GATEWAY;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public boolean running() {
            return started;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            started = false;
        }

        @Override
        public LocalNetHealth health() {
            return new LocalNetHealth(id(), family(), configured(), enabled(), running(), 0, 0, 0, "", "", null, List.of());
        }

        @Override
        public LocalNetReplayResult replay(byte[] payload) {
            return new LocalNetReplayResult(id(), family(), true, "ok", "", null);
        }
    }
}
