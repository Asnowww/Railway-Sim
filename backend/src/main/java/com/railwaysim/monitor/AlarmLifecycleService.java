package com.railwaysim.monitor;

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
import org.springframework.stereotype.Service;

@Service
public class AlarmLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(AlarmLifecycleService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, AlarmRecord> recordsById = new LinkedHashMap<>();
    private final Map<String, String> activeIdByBaseKey = new LinkedHashMap<>();
    private final Map<String, Integer> occurrenceByBaseKey = new LinkedHashMap<>();

    public AlarmLifecycleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public synchronized List<Alarm> reconcile(String runId, List<Alarm> candidates, Instant now) {
        Set<String> seenBaseKeys = new HashSet<>();
        for (Alarm candidate : candidates) {
            String baseKey = runId + "|" + candidate.id();
            seenBaseKeys.add(baseKey);
            String activeId = activeIdByBaseKey.get(baseKey);
            AlarmRecord current = activeId == null ? null : recordsById.get(activeId);
            if (current == null || current.state() == AlarmLifecycleState.CLEARED) {
                int occurrence = occurrenceByBaseKey.merge(baseKey, 1, Integer::sum);
                String id = runId + ":" + candidate.id() + ":" + occurrence;
                current = new AlarmRecord(
                    id, runId, candidate.id(), candidate.sourceModule(), candidate.locationRef(),
                    candidate.level(), candidate.title(), candidate.detail(), AlarmLifecycleState.RAISED,
                    now, now, null, null, null);
                recordsById.put(id, current);
                activeIdByBaseKey.put(baseKey, id);
            } else {
                current = new AlarmRecord(
                    current.id(), current.simulationRunId(), current.alarmCode(),
                    candidate.sourceModule(), candidate.locationRef(), candidate.level(),
                    candidate.title(), candidate.detail(), current.state(), current.raisedAt(), now,
                    current.acknowledgedAt(), current.acknowledgedBy(), null);
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
                    current.acknowledgedAt(), current.acknowledgedBy(), now);
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
            operator == null || operator.isBlank() ? "simulation" : operator, null);
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
                  acknowledged_at, acknowledged_by, cleared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  level = VALUES(level), title = VALUES(title), detail_text = VALUES(detail_text),
                  state = VALUES(state), confirmed = VALUES(confirmed),
                  last_seen_at = VALUES(last_seen_at), acknowledged_at = VALUES(acknowledged_at),
                  acknowledged_by = VALUES(acknowledged_by), cleared_at = VALUES(cleared_at)
                """,
                record.id(), record.simulationRunId(), record.alarmCode(), record.sourceModule(),
                record.locationRef(), record.level(), record.title(), record.detail(),
                record.state().name(), record.state() == AlarmLifecycleState.ACKNOWLEDGED,
                Timestamp.from(record.raisedAt()), Timestamp.from(record.lastSeenAt()),
                timestamp(record.acknowledgedAt()), record.acknowledgedBy(), timestamp(record.clearedAt()));
        } catch (DataAccessException ex) {
            log.warn("Alarm lifecycle persistence failed: {}", ex.getMessage());
        }
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
