package com.railwaysim.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.config.RecoveryProperties;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ServiceHealthServiceTests {
    private JdbcTemplate jdbc;
    private ServiceHealthService service;
    private RecoveryProperties properties;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:service-health;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS service_health_record");
        jdbc.execute("""
            CREATE TABLE service_health_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, service_id VARCHAR(64) NOT NULL UNIQUE,
              state VARCHAR(32) NOT NULL, data_quality VARCHAR(32) NOT NULL,
              source_timestamp TIMESTAMP NULL, observed_at TIMESTAMP NOT NULL,
              simulation_run_id VARCHAR(64), last_accepted_tick BIGINT NOT NULL,
              topology_hash VARCHAR(128), config_hash VARCHAR(128), model_version VARCHAR(128),
              parameter_version VARCHAR(128), reason_text VARCHAR(512), recovery_gate_json JSON
            )
            """);
        jdbc.execute("DROP TABLE IF EXISTS service_health_baseline");
        jdbc.execute("""
            CREATE TABLE service_health_baseline (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, service_id VARCHAR(64) NOT NULL UNIQUE,
              simulation_run_id VARCHAR(64), last_accepted_tick BIGINT NOT NULL,
              topology_hash VARCHAR(128) NOT NULL, config_hash VARCHAR(128) NOT NULL,
              model_version VARCHAR(128) NOT NULL, parameter_version VARCHAR(128) NOT NULL,
              source_timestamp TIMESTAMP NULL
            )
            """);
        properties = new RecoveryProperties();
        properties.setStaleAfterMillis(1000);
        properties.setMaximumTickLag(1);
        service = new ServiceHealthService(properties, jdbc, new ObjectMapper());
    }

    @Test
    void recoveryRequiresRunTickAndAllVersionMetadataToMatchLastGoodBaseline() {
        Instant now = Instant.parse("2026-07-12T00:00:00Z");
        ServiceHealthObservation good = observation(
            "UP", "GOOD", now, "run-1", 10, "top-a", "cfg-a", "model-a", "param-a");
        assertThat(service.observe(good, now).state()).isEqualTo(ServiceHealthState.UP);

        ServiceHealthObservation fallback = observation(
            "FALLBACK", "FALLBACK", now.plusMillis(100), "run-1", 10,
            "top-a", "cfg-a", "model-a", "param-a");
        assertThat(service.observe(fallback, now.plusMillis(100)).state())
            .isEqualTo(ServiceHealthState.FALLBACK);

        ServiceHealthObservation wrongConfig = observation(
            "UP", "GOOD", now.plusMillis(200), "run-1", 11,
            "top-a", "cfg-b", "model-a", "param-a");
        assertThat(service.observe(wrongConfig, now.plusMillis(200)).state())
            .isEqualTo(ServiceHealthState.RECOVERING);
        ServiceHealthRecord rejected = service.checkRecovery(
            "vehicle-runtime-9300", "run-1", 11, now.plusMillis(250));
        assertThat(rejected.state()).isEqualTo(ServiceHealthState.RECOVERING);
        assertThat(rejected.recoveryGate().rejectionReasons())
            .containsExactly("CONFIG_HASH_MISMATCH_OR_MISSING");

        ServiceHealthObservation recovered = observation(
            "UP", "GOOD", now.plusMillis(300), "run-1", 12,
            "top-a", "cfg-a", "model-a", "param-a");
        service.observe(recovered, now.plusMillis(300));
        ServiceHealthRecord accepted = service.checkRecovery(
            "vehicle-runtime-9300", "run-1", 12, now.plusMillis(350));
        assertThat(accepted.state()).isEqualTo(ServiceHealthState.UP);
        assertThat(accepted.recoveryGate().accepted()).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT state FROM service_health_record", String.class)).isEqualTo("UP");

        ServiceHealthService restarted = new ServiceHealthService(
            properties, jdbc, new ObjectMapper());
        restarted.loadPersistedState();
        assertThat(restarted.require("vehicle-runtime-9300").state())
            .isEqualTo(ServiceHealthState.UP);
        assertThat(jdbc.queryForObject(
            "SELECT config_hash FROM service_health_baseline", String.class)).isEqualTo("cfg-a");
    }

    @Test
    void oldHeartbeatBecomesStale() {
        Instant now = Instant.parse("2026-07-12T00:00:02Z");
        ServiceHealthRecord record = service.observe(observation(
            "UP", "GOOD", now.minusSeconds(2), "run-1", 1,
            "top-a", "cfg-a", "model-a", "param-a"), now);
        assertThat(record.state()).isEqualTo(ServiceHealthState.STALE);
    }

    @Test
    void explicitFallbackTakesPriorityOverHeartbeatAge() {
        Instant now = Instant.parse("2026-07-12T00:00:02Z");
        ServiceHealthRecord record = service.observe(observation(
            "FALLBACK", "FALLBACK", now.minusSeconds(2), "run-1", 1,
            "top-a", "cfg-a", "model-a", "param-a"), now);
        assertThat(record.state()).isEqualTo(ServiceHealthState.FALLBACK);
    }

    private ServiceHealthObservation observation(
        String heartbeat, String quality, Instant sourceTime, String runId, long tick,
        String topology, String config, String model, String parameter
    ) {
        return new ServiceHealthObservation(
            "vehicle-runtime-9300", true, heartbeat, quality, sourceTime, runId, tick,
            topology, config, model, parameter, heartbeat);
    }
}
