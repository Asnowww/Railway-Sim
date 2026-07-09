package com.railwaysim.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.api.dto.OperationLogEntry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApiOperationLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiOperationLogService.class);
    private static final int PERSIST_QUEUE_CAPACITY = 256;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final List<OperationLogEntry> entries = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor persistenceExecutor;
    private final AtomicBoolean persistenceWarningLogged = new AtomicBoolean();
    private final AtomicBoolean queueWarningLogged = new AtomicBoolean();

    public ApiOperationLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.persistenceExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(PERSIST_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "api-operation-log-persist");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public OperationLogEntry record(
        String operator,
        String operationType,
        String targetRef,
        String beforeState,
        String afterState,
        String reason,
        String traceId
    ) {
        OperationLogEntry entry = new OperationLogEntry(
            operator == null || operator.isBlank() ? "simulation" : operator,
            operationType,
            targetRef,
            beforeState,
            afterState,
            reason,
            traceId,
            Instant.now()
        );
        entries.add(entry);
        enqueuePersistence(() -> persistOperation(entry));
        return entry;
    }

    public List<OperationLogEntry> entries() {
        return List.copyOf(entries);
    }

    public List<OperationLogEntry> entriesForTarget(String targetRef) {
        return entries.stream()
            .filter(entry -> entry.targetRef().equals(targetRef))
            .toList();
    }

    public void recordPowerOperation(OperationLogEntry entry, String sectionId) {
        enqueuePersistence(() -> persistPowerOperation(entry, sectionId));
    }

    public void recordTrainFault(OperationLogEntry entry, String trainId, String faultCode, int faultLevel, String state) {
        if (!"INJECTED".equals(state)) {
            return;
        }
        enqueuePersistence(() -> persistTrainFault(entry, trainId, faultCode, faultLevel));
    }

    @PreDestroy
    public void shutdown() {
        persistenceExecutor.shutdownNow();
    }

    private void enqueuePersistence(Runnable task) {
        try {
            // 审计落库是旁路能力，不能阻塞车辆/供电控制接口；数据库慢或不可用时只丢弃持久化任务。
            persistenceExecutor.execute(task);
        } catch (RejectedExecutionException ex) {
            if (queueWarningLogged.compareAndSet(false, true)) {
                log.warn("API operation log persistence queue full; subsequent database audit writes may be skipped");
            }
        }
    }

    private void persistPowerOperation(OperationLogEntry entry, String sectionId) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO power_operation_log (
                      section_id, operation_type, before_state, after_state,
                      operator_name, detail_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                sectionId,
                entry.operationType(),
                entry.beforeState(),
                entry.afterState(),
                entry.operator(),
                detailJson(entry),
                Timestamp.from(entry.createdAt())
            );
        } catch (DataAccessException ex) {
            logPersistenceWarning(ex);
        }
    }

    private void persistTrainFault(OperationLogEntry entry, String trainId, String faultCode, int faultLevel) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO train_fault_record (
                      train_id, fault_code, fault_level, self_check_status,
                      available_operation_mode, detail_text, raised_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                trainId,
                faultCode,
                faultLevel,
                "FAIL",
                "NO_DEPARTURE",
                entry.reason(),
                Timestamp.from(entry.createdAt())
            );
        } catch (DataAccessException ex) {
            logPersistenceWarning(ex);
        }
    }

    private void persistOperation(OperationLogEntry entry) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO operation_log (
                      operator_name, operation_type, target_ref, detail_json, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                entry.operator(),
                entry.operationType(),
                entry.targetRef(),
                detailJson(entry),
                Timestamp.from(entry.createdAt())
            );
        } catch (DataAccessException ex) {
            logPersistenceWarning(ex);
        }
    }

    private String detailJson(OperationLogEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private void logPersistenceWarning(DataAccessException ex) {
        if (persistenceWarningLogged.compareAndSet(false, true)) {
            log.warn("API operation log persistence skipped after database write failure: {}", ex.getMessage());
        }
    }
}
