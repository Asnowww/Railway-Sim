package com.railwaysim.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.config.RecoveryProperties;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ServiceHealthService {
    private static final Logger log = LoggerFactory.getLogger(ServiceHealthService.class);

    private final RecoveryProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, ServiceHealthRecord> records = new LinkedHashMap<>();
    private final Map<String, ServiceHealthObservation> lastGoodBaselines = new LinkedHashMap<>();
    private final Map<String, ServiceHealthObservation> latestObservations = new LinkedHashMap<>();
    private final Map<String, Instant> lastPersistedAt = new LinkedHashMap<>();

    public ServiceHealthService(
        RecoveryProperties properties, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadPersistedState() {
        try {
            records.clear();
            jdbcTemplate.query("""
                SELECT service_id, state, data_quality, source_timestamp, observed_at,
                       simulation_run_id, last_accepted_tick, topology_hash, config_hash,
                       model_version, parameter_version, reason_text, recovery_gate_json
                  FROM service_health_record
                """, rs -> {
                RecoveryGate gate = recoveryGate(rs.getString("recovery_gate_json"));
                ServiceHealthRecord record = new ServiceHealthRecord(
                    rs.getString("service_id"), ServiceHealthState.valueOf(rs.getString("state")),
                    rs.getString("data_quality"), instant(rs.getTimestamp("source_timestamp")),
                    instant(rs.getTimestamp("observed_at")), rs.getString("simulation_run_id"),
                    rs.getLong("last_accepted_tick"), rs.getString("topology_hash"),
                    rs.getString("config_hash"), rs.getString("model_version"),
                    rs.getString("parameter_version"), rs.getString("reason_text"), gate);
                records.put(record.serviceId(), record);
                latestObservations.put(record.serviceId(), observation(record));
                lastPersistedAt.put(record.serviceId(), record.observedAt());
            });
            lastGoodBaselines.clear();
            jdbcTemplate.query("""
                SELECT service_id, simulation_run_id, last_accepted_tick, topology_hash,
                       config_hash, model_version, parameter_version, source_timestamp
                  FROM service_health_baseline
                """, rs -> {
                String serviceId = rs.getString("service_id");
                lastGoodBaselines.put(serviceId, new ServiceHealthObservation(
                    serviceId, true, "UP", "GOOD", instant(rs.getTimestamp("source_timestamp")),
                    rs.getString("simulation_run_id"), rs.getLong("last_accepted_tick"),
                    rs.getString("topology_hash"), rs.getString("config_hash"),
                    rs.getString("model_version"), rs.getString("parameter_version"),
                    "RESTORED_LAST_GOOD_BASELINE"));
            });
        } catch (DataAccessException ex) {
            log.warn("Service health restore skipped: {}", ex.getMessage());
        }
    }

    public synchronized ServiceHealthRecord observe(ServiceHealthObservation observation, Instant now) {
        latestObservations.put(observation.serviceId(), observation);
        ServiceHealthRecord previous = records.get(observation.serviceId());
        ServiceHealthState rawState = rawState(observation, now);
        ServiceHealthState state = rawState;
        RecoveryGate gate = null;
        if (rawState == ServiceHealthState.UP && previous != null
            && previous.state() != ServiceHealthState.UP) {
            state = ServiceHealthState.RECOVERING;
            gate = pendingGate("EXPLICIT_RECOVERY_CHECK_REQUIRED");
        }
        if (state == ServiceHealthState.UP) {
            lastGoodBaselines.put(observation.serviceId(), observation);
        }
        ServiceHealthRecord record = record(observation, state, now, gate);
        records.put(record.serviceId(), record);
        if (shouldPersist(previous, record, now)) {
            persist(record);
            if (state == ServiceHealthState.UP) persistBaseline(observation);
            lastPersistedAt.put(record.serviceId(), now);
        }
        return record;
    }

    public synchronized ServiceHealthRecord checkRecovery(
        String serviceId, String expectedRunId, long expectedTick, Instant now
    ) {
        ServiceHealthRecord current = require(serviceId);
        if (current.state() != ServiceHealthState.RECOVERING) {
            throw new IllegalStateException("Service is not recovering: " + serviceId);
        }
        ServiceHealthObservation observation = latestObservations.get(serviceId);
        ServiceHealthObservation baseline = lastGoodBaselines.get(serviceId);
        RecoveryGate gate = evaluate(observation, baseline, expectedRunId, expectedTick);
        ServiceHealthState state = gate.accepted()
            ? ServiceHealthState.UP : ServiceHealthState.RECOVERING;
        ServiceHealthRecord updated = record(observation, state, now, gate);
        records.put(serviceId, updated);
        if (gate.accepted()) {
            lastGoodBaselines.put(serviceId, observation);
            persistBaseline(observation);
        }
        persist(updated);
        lastPersistedAt.put(serviceId, now);
        return updated;
    }

    public synchronized List<ServiceHealthRecord> records() {
        return List.copyOf(records.values());
    }

    public synchronized ServiceHealthRecord require(String serviceId) {
        ServiceHealthRecord record = records.get(serviceId);
        if (record == null) throw new IllegalArgumentException("Service health not found: " + serviceId);
        return record;
    }

    private ServiceHealthState rawState(ServiceHealthObservation observation, Instant now) {
        if (!observation.external()) return ServiceHealthState.UP;
        if ("FALLBACK".equals(observation.dataQuality())
            || "FALLBACK".equals(observation.heartbeatStatus())) {
            return ServiceHealthState.FALLBACK;
        }
        if (observation.sourceTimestamp() == null
            || Duration.between(observation.sourceTimestamp(), now).toMillis()
                > properties.getStaleAfterMillis()) {
            return ServiceHealthState.STALE;
        }
        if (!"UP".equals(observation.heartbeatStatus())
            || !"GOOD".equals(observation.dataQuality())) {
            return ServiceHealthState.DEGRADED;
        }
        return ServiceHealthState.UP;
    }

    private RecoveryGate evaluate(
        ServiceHealthObservation observation, ServiceHealthObservation baseline,
        String expectedRunId, long expectedTick
    ) {
        List<String> reasons = new ArrayList<>();
        boolean run = expectedRunId != null && !expectedRunId.isBlank()
            && expectedRunId.equals(observation.simulationRunId());
        boolean tick = observation.lastAcceptedTick() <= expectedTick
            && observation.lastAcceptedTick() >= expectedTick - properties.getMaximumTickLag();
        boolean topology = matchesRequired(
            baseline == null ? "" : baseline.topologyHash(), observation.topologyHash());
        boolean config = matchesRequired(
            baseline == null ? "" : baseline.configHash(), observation.configHash());
        boolean model = matchesRequired(
            baseline == null ? "" : baseline.modelVersion(), observation.modelVersion());
        boolean parameter = matchesRequired(
            baseline == null ? "" : baseline.parameterVersion(), observation.parameterVersion());
        addReason(reasons, run, "RUN_ID_MISMATCH");
        addReason(reasons, tick, "TICK_WATERMARK_MISMATCH");
        addReason(reasons, topology, "TOPOLOGY_HASH_MISMATCH_OR_MISSING");
        addReason(reasons, config, "CONFIG_HASH_MISMATCH_OR_MISSING");
        addReason(reasons, model, "MODEL_VERSION_MISMATCH_OR_MISSING");
        addReason(reasons, parameter, "PARAMETER_VERSION_MISMATCH_OR_MISSING");
        return new RecoveryGate(
            reasons.isEmpty(), run, tick, topology, config, model, parameter, reasons);
    }

    private boolean matchesRequired(String expected, String actual) {
        if (actual == null || actual.isBlank()) return false;
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private void addReason(List<String> reasons, boolean matched, String reason) {
        if (!matched) reasons.add(reason);
    }

    private RecoveryGate pendingGate(String reason) {
        return new RecoveryGate(false, false, false, false, false, false, false, List.of(reason));
    }

    private ServiceHealthRecord record(
        ServiceHealthObservation observation, ServiceHealthState state, Instant now, RecoveryGate gate
    ) {
        return new ServiceHealthRecord(
            observation.serviceId(), state, observation.dataQuality(), observation.sourceTimestamp(),
            now, observation.simulationRunId(), observation.lastAcceptedTick(),
            observation.topologyHash(), observation.configHash(), observation.modelVersion(),
            observation.parameterVersion(), observation.reason(), gate);
    }

    private void persist(ServiceHealthRecord record) {
        try {
            jdbcTemplate.update("""
                INSERT INTO service_health_record (
                  service_id, state, data_quality, source_timestamp, observed_at,
                  simulation_run_id, last_accepted_tick, topology_hash, config_hash,
                  model_version, parameter_version, reason_text, recovery_gate_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  state = VALUES(state), data_quality = VALUES(data_quality),
                  source_timestamp = VALUES(source_timestamp), observed_at = VALUES(observed_at),
                  simulation_run_id = VALUES(simulation_run_id),
                  last_accepted_tick = VALUES(last_accepted_tick),
                  topology_hash = VALUES(topology_hash), config_hash = VALUES(config_hash),
                  model_version = VALUES(model_version), parameter_version = VALUES(parameter_version),
                  reason_text = VALUES(reason_text), recovery_gate_json = VALUES(recovery_gate_json)
                """, record.serviceId(), record.state().name(), record.dataQuality(),
                timestamp(record.sourceTimestamp()), Timestamp.from(record.observedAt()),
                record.simulationRunId(), record.lastAcceptedTick(), record.topologyHash(),
                record.configHash(), record.modelVersion(), record.parameterVersion(), record.reason(),
                json(record.recoveryGate()));
        } catch (DataAccessException ex) {
            log.warn("Service health persistence failed: {}", ex.getMessage());
        }
    }

    private boolean shouldPersist(
        ServiceHealthRecord previous, ServiceHealthRecord current, Instant now
    ) {
        if (previous == null || previous.state() != current.state()) return true;
        if (!java.util.Objects.equals(previous.dataQuality(), current.dataQuality())) return true;
        if (!java.util.Objects.equals(previous.simulationRunId(), current.simulationRunId())) return true;
        if (!java.util.Objects.equals(previous.topologyHash(), current.topologyHash())
            || !java.util.Objects.equals(previous.configHash(), current.configHash())
            || !java.util.Objects.equals(previous.modelVersion(), current.modelVersion())
            || !java.util.Objects.equals(previous.parameterVersion(), current.parameterVersion())) {
            return true;
        }
        Instant persistedAt = lastPersistedAt.get(current.serviceId());
        return persistedAt == null || Duration.between(persistedAt, now).toMillis() >= 1000;
    }

    private void persistBaseline(ServiceHealthObservation observation) {
        try {
            jdbcTemplate.update("""
                INSERT INTO service_health_baseline (
                  service_id, simulation_run_id, last_accepted_tick, topology_hash,
                  config_hash, model_version, parameter_version, source_timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  simulation_run_id = VALUES(simulation_run_id),
                  last_accepted_tick = VALUES(last_accepted_tick),
                  topology_hash = VALUES(topology_hash), config_hash = VALUES(config_hash),
                  model_version = VALUES(model_version), parameter_version = VALUES(parameter_version),
                  source_timestamp = VALUES(source_timestamp)
                """, observation.serviceId(), observation.simulationRunId(),
                observation.lastAcceptedTick(), observation.topologyHash(), observation.configHash(),
                observation.modelVersion(), observation.parameterVersion(),
                timestamp(observation.sourceTimestamp()));
        } catch (DataAccessException ex) {
            log.warn("Service health baseline persistence failed: {}", ex.getMessage());
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private ServiceHealthObservation observation(ServiceHealthRecord record) {
        return new ServiceHealthObservation(
            record.serviceId(), true,
            record.state() == ServiceHealthState.UP ? "UP" : record.state().name(),
            record.dataQuality(), record.sourceTimestamp(), record.simulationRunId(),
            record.lastAcceptedTick(), record.topologyHash(), record.configHash(),
            record.modelVersion(), record.parameterVersion(), record.reason());
    }

    private RecoveryGate recoveryGate(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            String normalized = json;
            if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
                normalized = objectMapper.readValue(normalized, String.class);
            }
            return objectMapper.readValue(normalized, RecoveryGate.class);
        } catch (JsonProcessingException ex) {
            log.warn("Recovery gate restore failed: {}", ex.getMessage());
            return null;
        }
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize recovery gate", ex);
        }
    }
}
