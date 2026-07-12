package com.railwaysim.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AlarmLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(AlarmLifecycleService.class);

    private final JdbcTemplate jdbcTemplate;
    private final FaultImpactCatalog faultImpactCatalog;
    private final ObjectMapper objectMapper;
    private final Map<String, AlarmRecord> recordsById = new LinkedHashMap<>();
    private final Map<String, String> activeIdByBaseKey = new LinkedHashMap<>();
    private final Map<String, Integer> occurrenceByBaseKey = new LinkedHashMap<>();

    @Autowired
    public AlarmLifecycleService(
        JdbcTemplate jdbcTemplate, FaultImpactCatalog faultImpactCatalog, ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.faultImpactCatalog = faultImpactCatalog;
        this.objectMapper = objectMapper;
    }

    public AlarmLifecycleService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new FaultImpactCatalog(), new ObjectMapper());
    }

    @PostConstruct
    void loadPersistedRecords() {
        try {
            List<AlarmRecord> loaded = jdbcTemplate.query("""
                SELECT alarm_id, simulation_run_id, alarm_code, source_module, location_ref,
                       level, title, detail_text, state, raised_at, last_seen_at,
                       acknowledged_at, acknowledged_by, cleared_at,
                       affected_train_ids_json, affected_section_ids_json,
                       safety_action, clear_condition, recovery_condition
                  FROM alarm_record ORDER BY raised_at, alarm_id
                """, (rs, row) -> {
                int level = rs.getInt("level");
                FaultImpact impact = new FaultImpact(
                    level >= 3 ? "CRITICAL" : level == 2 ? "WARNING" : "INFO",
                    stringList(rs.getString("affected_train_ids_json")),
                    stringList(rs.getString("affected_section_ids_json")),
                    rs.getString("safety_action"), rs.getString("clear_condition"),
                    rs.getString("recovery_condition"));
                return new AlarmRecord(
                    rs.getString("alarm_id"), rs.getString("simulation_run_id"),
                    rs.getString("alarm_code"), rs.getString("source_module"),
                    rs.getString("location_ref"), level, rs.getString("title"),
                    rs.getString("detail_text"), AlarmLifecycleState.valueOf(rs.getString("state")),
                    instant(rs.getTimestamp("raised_at")), instant(rs.getTimestamp("last_seen_at")),
                    instant(rs.getTimestamp("acknowledged_at")), rs.getString("acknowledged_by"),
                    instant(rs.getTimestamp("cleared_at")), impact);
            });
            recordsById.clear();
            activeIdByBaseKey.clear();
            occurrenceByBaseKey.clear();
            for (AlarmRecord record : loaded) {
                recordsById.put(record.id(), record);
                String baseKey = record.simulationRunId() + "|" + record.alarmCode();
                occurrenceByBaseKey.merge(baseKey, occurrence(record.id()), Math::max);
                if (record.state() != AlarmLifecycleState.CLEARED) {
                    activeIdByBaseKey.put(baseKey, record.id());
                }
            }
        } catch (DataAccessException ex) {
            log.warn("Alarm lifecycle restore skipped: {}", ex.getMessage());
        }
    }

    public synchronized List<Alarm> reconcile(String runId, List<Alarm> candidates, Instant now) {
        Set<String> seenBaseKeys = new HashSet<>();
        for (Alarm candidate : candidates) {
            String baseKey = runId + "|" + candidate.id();
            seenBaseKeys.add(baseKey);
            String activeId = activeIdByBaseKey.get(baseKey);
            AlarmRecord current = activeId == null ? null : recordsById.get(activeId);
            FaultImpact impact = candidate.impact() == null
                ? faultImpactCatalog.resolve(candidate) : candidate.impact();
            if (current == null || current.state() == AlarmLifecycleState.CLEARED) {
                int occurrence = occurrenceByBaseKey.merge(baseKey, 1, Integer::sum);
                String id = runId + ":" + candidate.id() + ":" + occurrence;
                current = new AlarmRecord(
                    id, runId, candidate.id(), candidate.sourceModule(), candidate.locationRef(),
                    candidate.level(), candidate.title(), candidate.detail(), AlarmLifecycleState.RAISED,
                    now, now, null, null, null, impact);
                recordsById.put(id, current);
                activeIdByBaseKey.put(baseKey, id);
            } else {
                current = new AlarmRecord(
                    current.id(), current.simulationRunId(), current.alarmCode(),
                    candidate.sourceModule(), candidate.locationRef(), candidate.level(),
                    candidate.title(), candidate.detail(), current.state(), current.raisedAt(), now,
                    current.acknowledgedAt(), current.acknowledgedBy(), null, impact);
                recordsById.put(current.id(), current);
            }
            persist(current);
        }

        for (Map.Entry<String, String> active : new ArrayList<>(activeIdByBaseKey.entrySet())) {
            if (seenBaseKeys.contains(active.getKey())) {
                continue;
            }
            AlarmRecord current = recordsById.get(active.getValue());
            if (current != null && current.state() != AlarmLifecycleState.CLEARED) {
                AlarmRecord cleared = new AlarmRecord(
                    current.id(), current.simulationRunId(), current.alarmCode(), current.sourceModule(),
                    current.locationRef(), current.level(), current.title(), current.detail(),
                    AlarmLifecycleState.CLEARED, current.raisedAt(), current.lastSeenAt(),
                    current.acknowledgedAt(), current.acknowledgedBy(), now, current.impact());
                recordsById.put(cleared.id(), cleared);
                persist(cleared);
            }
            activeIdByBaseKey.remove(active.getKey());
        }
        return activeIdByBaseKey.values().stream()
            .map(recordsById::get)
            .filter(record -> record != null && record.state() != AlarmLifecycleState.CLEARED)
            .map(AlarmRecord::toAlarm)
            .toList();
    }

    public synchronized List<AlarmRecord> records(String runId) {
        return recordsById.values().stream()
            .filter(record -> runId == null || runId.isBlank() || runId.equals(record.simulationRunId()))
            .toList();
    }

    public synchronized AlarmRecord acknowledge(String id, String operator, Instant now) {
        AlarmRecord current = require(id);
        if (current.state() == AlarmLifecycleState.CLEARED) {
            throw new IllegalStateException("Cleared alarm cannot be acknowledged: " + id);
        }
        if (current.state() == AlarmLifecycleState.ACKNOWLEDGED) {
            return current;
        }
        AlarmRecord acknowledged = new AlarmRecord(
            current.id(), current.simulationRunId(), current.alarmCode(), current.sourceModule(),
            current.locationRef(), current.level(), current.title(), current.detail(),
            AlarmLifecycleState.ACKNOWLEDGED, current.raisedAt(), current.lastSeenAt(), now,
            operator == null || operator.isBlank() ? "simulation" : operator, null,
            current.impact());
        recordsById.put(id, acknowledged);
        persist(acknowledged);
        return acknowledged;
    }

    private AlarmRecord require(String id) {
        AlarmRecord record = recordsById.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Alarm not found: " + id);
        }
        return record;
    }

    private void persist(AlarmRecord record) {
        try {
            jdbcTemplate.update("""
                INSERT INTO alarm_record (
                  alarm_id, simulation_run_id, alarm_code, source_module, location_ref,
                  level, title, detail_text, state, confirmed, raised_at, last_seen_at,
                  acknowledged_at, acknowledged_by, cleared_at, affected_train_ids_json,
                  affected_section_ids_json, safety_action, clear_condition, recovery_condition
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  level = VALUES(level), title = VALUES(title), detail_text = VALUES(detail_text),
                  state = VALUES(state), confirmed = VALUES(confirmed),
                  last_seen_at = VALUES(last_seen_at), acknowledged_at = VALUES(acknowledged_at),
                  acknowledged_by = VALUES(acknowledged_by), cleared_at = VALUES(cleared_at),
                  affected_train_ids_json = VALUES(affected_train_ids_json),
                  affected_section_ids_json = VALUES(affected_section_ids_json),
                  safety_action = VALUES(safety_action), clear_condition = VALUES(clear_condition),
                  recovery_condition = VALUES(recovery_condition)
                """,
                record.id(), record.simulationRunId(), record.alarmCode(), record.sourceModule(),
                record.locationRef(), record.level(), record.title(), record.detail(),
                record.state().name(), record.state() == AlarmLifecycleState.ACKNOWLEDGED,
                Timestamp.from(record.raisedAt()), Timestamp.from(record.lastSeenAt()),
                timestamp(record.acknowledgedAt()), record.acknowledgedBy(), timestamp(record.clearedAt()),
                json(record.impact().affectedTrainIds()), json(record.impact().affectedSectionIds()),
                record.impact().safetyAction(), record.impact().clearCondition(),
                record.impact().recoveryCondition());
        } catch (DataAccessException ex) {
            log.warn("Alarm lifecycle persistence failed: {}", ex.getMessage());
        }
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize fault impact", ex);
        }
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            String normalized = json;
            if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
                normalized = objectMapper.readValue(normalized, String.class);
            }
            return objectMapper.readValue(normalized, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            log.warn("Alarm impact list restore failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private int occurrence(String alarmId) {
        int separator = alarmId.lastIndexOf(':');
        if (separator < 0) return 1;
        try {
            return Integer.parseInt(alarmId.substring(separator + 1));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
