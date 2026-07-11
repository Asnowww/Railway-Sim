package com.railwaysim.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.api.dto.OperationLogEntry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApiOperationLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiOperationLogService.class);
    private static final int PERSIST_QUEUE_CAPACITY = 256;
    private static final int MAX_RETRY = 3;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final List<OperationLogEntry> entries = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor persistenceExecutor;
    private final AtomicBoolean persistenceWarningLogged = new AtomicBoolean();
    private final AtomicBoolean queueWarningLogged = new AtomicBoolean();
    private final AtomicLong totalPersisted = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    public ApiOperationLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.persistenceExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(PERSIST_QUEUE_CAPACITY),
            r -> { Thread t = new Thread(r, "api-operation-log-persist"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    }

    public OperationLogEntry record(String operator, String operationType, String targetRef,
        String beforeState, String afterState, String reason, String traceId) {
        OperationLogEntry entry = new OperationLogEntry(
            operator == null || operator.isBlank() ? "simulation" : operator,
            operationType, targetRef, beforeState, afterState, reason, traceId, Instant.now());
        entries.add(entry);
        enqueuePersistence(entry, () -> persistOperation(entry));
        return entry;
    }

    public OperationLogEntry recordWithRunId(String operator, String operationType, String targetRef,
        String beforeState, String afterState, String reason, String traceId, String runId, long tick) {
        OperationLogEntry entry = new OperationLogEntry(
            operator == null || operator.isBlank() ? "simulation" : operator,
            operationType, targetRef, beforeState, afterState, reason, traceId, Instant.now());
        entries.add(entry);
        enqueuePersistence(entry, () -> persistOperationFull(entry, runId, tick));
        return entry;
    }

    public List<OperationLogEntry> entries() { return List.copyOf(entries); }

    public List<OperationLogEntry> entriesForTarget(String targetRef) {
        return entries.stream().filter(e -> e.targetRef().equals(targetRef)).toList();
    }

    public long totalPersisted() { return totalPersisted.get(); }
    public long totalFailed() { return totalFailed.get(); }
    public long pendingCount() { return entries.stream().filter(e -> "PENDING".equals(e.status())).count(); }

    /**
     * 同步写入审计日志（危险操作使用）。
     *
     * <p>该方法是危险操作的 fail-closed 门禁：只有审计证据已经落库，
     * 调用方才能继续修改仿真状态。不允许回落到异步队列，否则会形成
     * “业务成功、证据丢失”的伪成功。
     */
    public OperationLogEntry recordSync(String operator, String operationType, String targetRef,
        String beforeState, String afterState, String reason, String traceId) {
        OperationLogEntry entry = new OperationLogEntry(
            operator == null || operator.isBlank() ? "simulation" : operator,
            operationType, targetRef, beforeState, afterState, reason, traceId, Instant.now());
        entries.add(entry);
        try {
            jdbcTemplate.update("""
                INSERT INTO operation_log (operator_name, operation_type, target_ref, detail_json,
                  status, retry_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, entry.operator(), entry.operationType(), entry.targetRef(), detailJson(entry),
                "PERSISTED", 0, Timestamp.from(entry.createdAt()));
            updateEntryStatus(entry, "PERSISTED", 0);
            totalPersisted.incrementAndGet();
        } catch (DataAccessException ex) {
            updateEntryStatus(entry, "FAILED", 0);
            totalFailed.incrementAndGet();
            logPersistenceWarning(ex);
            throw ex;
        }
        return entry;
    }

    public OperationLogEntry recordSyncWithRunId(String operator, String operationType, String targetRef,
        String beforeState, String afterState, String reason, String traceId, String runId, long tick) {
        OperationLogEntry entry = new OperationLogEntry(
            operator == null || operator.isBlank() ? "simulation" : operator,
            operationType, targetRef, beforeState, afterState, reason, traceId, Instant.now());
        entries.add(entry);
        try {
            jdbcTemplate.update("""
                INSERT INTO operation_log (operator_name, operation_type, target_ref, detail_json,
                  run_id, tick, trace_id, before_state, after_state, reason,
                  status, retry_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PERSISTED', 0, ?)
                """, entry.operator(), entry.operationType(), entry.targetRef(), detailJson(entry),
                runId, tick, entry.traceId(), entry.beforeState(), entry.afterState(), entry.reason(),
                Timestamp.from(entry.createdAt()));
            updateEntryStatus(entry, "PERSISTED", 0);
            totalPersisted.incrementAndGet();
            return entry;
        } catch (DataAccessException ex) {
            updateEntryStatus(entry, "FAILED", 0);
            totalFailed.incrementAndGet();
            logPersistenceWarning(ex);
            throw ex;
        }
    }

    public void recordPowerOperation(OperationLogEntry entry, String sectionId) {
        enqueuePersistence(entry, () -> persistPowerOperation(entry, sectionId));
    }

    public void recordTrainFault(OperationLogEntry entry, String trainId, String faultCode, int faultLevel, String state) {
        if (!"INJECTED".equals(state)) return;
        enqueuePersistence(entry, () -> persistTrainFault(entry, trainId, faultCode, faultLevel));
    }

    @PreDestroy
    public void shutdown() {
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
        }
    }

    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + Math.max(1, timeout.toNanos());
        while (System.nanoTime() < deadline) {
            if (persistenceExecutor.getActiveCount() == 0 && persistenceExecutor.getQueue().isEmpty()) {
                return true;
            }
            sleep(10);
        }
        return false;
    }

    private void enqueuePersistence(OperationLogEntry entry, Runnable task) {
        try { persistenceExecutor.execute(task); }
        catch (RejectedExecutionException ex) {
            updateEntryStatus(entry, "FAILED", 0);
            totalFailed.incrementAndGet();
            if (queueWarningLogged.compareAndSet(false, true))
                log.warn("API operation log persistence queue full; writes may be skipped");
        }
    }

    private void persistOperationFull(OperationLogEntry entry, String runId, long tick) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                jdbcTemplate.update("""
                    INSERT INTO operation_log (operator_name, operation_type, target_ref, detail_json,
                      run_id, tick, trace_id, before_state, after_state, reason, status, retry_count, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, entry.operator(), entry.operationType(), entry.targetRef(), detailJson(entry),
                    runId, tick, entry.traceId(), entry.beforeState(), entry.afterState(), entry.reason(),
                    "PERSISTED", attempt, Timestamp.from(entry.createdAt()));
                updateEntryStatus(entry, "PERSISTED", attempt);
                totalPersisted.incrementAndGet();
                return;
            } catch (DataAccessException ex) {
                if (attempt < MAX_RETRY - 1) { sleep(50L * (attempt + 1)); }
                else { markFailed(entry, attempt, ex); }
            }
        }
    }

    private void persistOperation(OperationLogEntry entry) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                jdbcTemplate.update("""
                    INSERT INTO operation_log (operator_name, operation_type, target_ref, detail_json,
                      status, retry_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, entry.operator(), entry.operationType(), entry.targetRef(), detailJson(entry),
                    "PERSISTED", attempt, Timestamp.from(entry.createdAt()));
                updateEntryStatus(entry, "PERSISTED", attempt);
                totalPersisted.incrementAndGet();
                return;
            } catch (DataAccessException ex) {
                if (attempt < MAX_RETRY - 1) { sleep(50L * (attempt + 1)); }
                else { markFailed(entry, attempt, ex); }
            }
        }
    }

    private void persistPowerOperation(OperationLogEntry entry, String sectionId) {
        persistSupplemental(entry, () -> jdbcTemplate.update(
            "INSERT INTO power_operation_log (section_id, operation_type, before_state, after_state, operator_name, detail_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            sectionId, entry.operationType(), entry.beforeState(), entry.afterState(), entry.operator(),
            detailJson(entry), Timestamp.from(entry.createdAt())
        ));
    }

    private void persistTrainFault(OperationLogEntry entry, String trainId, String faultCode, int faultLevel) {
        persistSupplemental(entry, () -> jdbcTemplate.update(
            "INSERT INTO train_fault_record (train_id, fault_code, fault_level, self_check_status, available_operation_mode, detail_text, raised_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            trainId, faultCode, faultLevel, "FAIL", "NO_DEPARTURE", entry.reason(), Timestamp.from(entry.createdAt())
        ));
    }

    private String detailJson(OperationLogEntry entry) {
        try { return objectMapper.writeValueAsString(entry); } catch (JsonProcessingException ex) { return "{}"; }
    }

    private void persistSupplemental(OperationLogEntry entry, Runnable write) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                write.run();
                return;
            } catch (DataAccessException ex) {
                if (attempt < MAX_RETRY - 1) {
                    sleep(50L * (attempt + 1));
                } else {
                    markFailed(entry, attempt, ex);
                }
            }
        }
    }

    private void markFailed(OperationLogEntry entry, int attempt, DataAccessException exception) {
        updateEntryStatus(entry, "FAILED", attempt);
        totalFailed.incrementAndGet();
        logPersistenceWarning(exception);
    }

    private void updateEntryStatus(OperationLogEntry entry, String status, int retryCount) {
        int idx = entries.indexOf(entry);
        if (idx >= 0) entries.set(idx, entry.withStatus(status).withRetry(retryCount));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void logPersistenceWarning(DataAccessException ex) {
        if (persistenceWarningLogged.compareAndSet(false, true))
            log.warn("API operation log persistence failed after retries: {}", ex.getMessage());
    }
}
