package com.railwaysim.simulation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the lifecycle of the dispatch-owned simulation run id. */
@Service
public class SimulationRunService {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunService.class);
    private final JdbcTemplate jdbcTemplate;

    public SimulationRunService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(String runId, Instant now) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        jdbcTemplate.update("""
            INSERT INTO simulation_run (run_id, status, created_at, last_tick)
            VALUES (?, 'CREATED', ?, 0)
            ON DUPLICATE KEY UPDATE run_id = run_id
            """, runId, Timestamp.from(now));
    }

    public void start(String runId, long tick, Instant now) {
        create(runId, now);
        jdbcTemplate.update("""
            UPDATE simulation_run
               SET status = 'RUNNING', started_at = COALESCE(started_at, ?),
                   ended_at = NULL, end_reason = NULL, last_tick = ?
             WHERE run_id = ?
            """, Timestamp.from(now), tick, runId);
    }

    public void pause(String runId, long tick) {
        updateActive(runId, SimulationRunStatus.PAUSED, tick);
    }

    public void complete(String runId, long tick, Instant now, String reason) {
        end(runId, SimulationRunStatus.COMPLETED, tick, now, reason);
    }

    public void cancelByReset(String runId, long tick, Instant now) {
        end(runId, SimulationRunStatus.CANCELLED_BY_RESET, tick, now, "RESET");
    }

    @Transactional
    public void rollover(String previousRunId, String nextRunId, long previousTick,
        Instant endedAt, Instant nextCreatedAt) {
        create(previousRunId, endedAt);
        cancelByReset(previousRunId, previousTick, endedAt);
        create(nextRunId, nextCreatedAt);
    }

    public void fail(String runId, long tick, Instant now, String reason) {
        end(runId, SimulationRunStatus.FAILED, tick, now, reason);
    }

    public Optional<SimulationRunRecord> find(String runId) {
        return jdbcTemplate.query("""
            SELECT run_id, status, created_at, started_at, ended_at, last_tick, end_reason
              FROM simulation_run WHERE run_id = ?
            """, (rs, row) -> map(rs), runId).stream().findFirst();
    }

    public List<SimulationRunRecord> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query("""
            SELECT run_id, status, created_at, started_at, ended_at, last_tick, end_reason
              FROM simulation_run ORDER BY created_at DESC LIMIT ?
            """, (rs, row) -> map(rs), safeLimit);
    }

    public Map<String, Object> summary(String runId) {
        SimulationRunRecord run = find(runId).orElseThrow(() ->
            new IllegalArgumentException("Simulation run not found: " + runId));
        return Map.of(
            "run", run,
            "vehicleSnapshotCount", count("train_physics_snapshot", runId),
            "controlDecisionCount", count("vehicle_control_command_log", runId),
            "powerSnapshotCount", count("power_section_record", runId),
            "stopResultCount", count("train_stop_result", runId),
            "faultCount", count("power_vehicle_fault_record", runId)
        );
    }

    public List<Map<String, Object>> trainSnapshots(String runId, int limit, int offset) {
        return page("SELECT * FROM train_physics_snapshot WHERE simulation_run_id = ? ORDER BY tick, train_id LIMIT ? OFFSET ?",
            runId, limit, offset);
    }

    public List<Map<String, Object>> controlDecisions(String runId, int limit, int offset) {
        return page("SELECT * FROM vehicle_control_command_log WHERE simulation_run_id = ? ORDER BY tick, train_id LIMIT ? OFFSET ?",
            runId, limit, offset);
    }

    public List<Map<String, Object>> powerSnapshots(String runId, int limit, int offset) {
        return page("SELECT * FROM power_section_record WHERE simulation_run_id = ? ORDER BY tick, section_id LIMIT ? OFFSET ?",
            runId, limit, offset);
    }

    public List<Map<String, Object>> stopResults(String runId, int limit, int offset) {
        return page("SELECT * FROM train_stop_result WHERE simulation_run_id = ? ORDER BY stable_at_tick, train_id LIMIT ? OFFSET ?",
            runId, limit, offset);
    }

    public TrainStopStatistics stopStatistics(
        String runId, String trainId, String stationId, int requiredSampleCount
    ) {
        String normalizedTrainId = normalizeFilter(trainId);
        String normalizedStationId = normalizeFilter(stationId);
        int minimumSamples = Math.max(1, requiredSampleCount);
        List<StopMetric> metrics = jdbcTemplate.query("""
            SELECT absolute_error_meters, success, overrun, emergency_brake, reason_code
              FROM train_stop_result
             WHERE simulation_run_id = ?
               AND (? IS NULL OR train_id = ?)
               AND (? IS NULL OR station_id = ?)
             ORDER BY stable_at_tick
            """, (rs, row) -> new StopMetric(
                rs.getDouble("absolute_error_meters"),
                rs.getBoolean("success"),
                rs.getBoolean("overrun"),
                rs.getBoolean("emergency_brake"),
                rs.getString("reason_code")
            ), runId, normalizedTrainId, normalizedTrainId, normalizedStationId, normalizedStationId);

        List<Double> sortedErrors = metrics.stream()
            .map(StopMetric::absoluteErrorMeters)
            .sorted(Comparator.naturalOrder())
            .toList();
        double mean = metrics.stream().mapToDouble(StopMetric::absoluteErrorMeters).average().orElse(0);
        double variance = metrics.stream()
            .mapToDouble(metric -> Math.pow(metric.absoluteErrorMeters() - mean, 2))
            .average().orElse(0);
        double p95 = sortedErrors.isEmpty() ? 0
            : sortedErrors.get(Math.max(0, (int) Math.ceil(sortedErrors.size() * 0.95) - 1));
        int successCount = (int) metrics.stream().filter(StopMetric::success).count();
        Map<String, Integer> reasonDistribution = new LinkedHashMap<>();
        metrics.forEach(metric -> reasonDistribution.merge(metric.reasonCode(), 1, Integer::sum));
        return new TrainStopStatistics(
            runId, normalizedTrainId, normalizedStationId, metrics.size(), minimumSamples,
            metrics.size() >= minimumSamples, mean, p95, variance, successCount,
            metrics.isEmpty() ? 0 : (double) successCount / metrics.size(),
            (int) metrics.stream().filter(StopMetric::overrun).count(),
            (int) metrics.stream().filter(StopMetric::emergencyBrake).count(),
            Map.copyOf(reasonDistribution));
    }

    public List<Map<String, Object>> faults(String runId, int limit, int offset) {
        return page("SELECT * FROM power_vehicle_fault_record WHERE simulation_run_id = ? ORDER BY tick, fault_id LIMIT ? OFFSET ?",
            runId, limit, offset);
    }

    public void recordTickBestEffort(String runId, long tick) {
        try {
            jdbcTemplate.update("UPDATE simulation_run SET last_tick = ? WHERE run_id = ?", tick, runId);
        } catch (DataAccessException ex) {
            log.warn("Simulation run tick watermark persistence failed: {}", ex.getMessage());
        }
    }

    private void updateActive(String runId, SimulationRunStatus status, long tick) {
        jdbcTemplate.update("UPDATE simulation_run SET status = ?, last_tick = ? WHERE run_id = ?",
            status.name(), tick, runId);
    }

    private long count(String table, String runId) {
        Long value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE simulation_run_id = ?", Long.class, runId);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> page(String sql, String runId, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        return jdbcTemplate.queryForList(sql, runId, safeLimit, safeOffset);
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void end(String runId, SimulationRunStatus status, long tick, Instant now, String reason) {
        jdbcTemplate.update("""
            UPDATE simulation_run
               SET status = ?, ended_at = ?, last_tick = ?, end_reason = ?
             WHERE run_id = ? AND ended_at IS NULL
            """, status.name(), Timestamp.from(now), tick, reason, runId);
    }

    private SimulationRunRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SimulationRunRecord(
            rs.getString("run_id"),
            SimulationRunStatus.valueOf(rs.getString("status")),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("ended_at")),
            rs.getLong("last_tick"),
            rs.getString("end_reason")
        );
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record StopMetric(
        double absoluteErrorMeters,
        boolean success,
        boolean overrun,
        boolean emergencyBrake,
        String reasonCode
    ) {
    }
}
