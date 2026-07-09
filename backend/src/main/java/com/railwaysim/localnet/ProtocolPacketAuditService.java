package com.railwaysim.localnet;

import com.railwaysim.config.LocalNetProperties;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.util.concurrent.ArrayBlockingQueue;
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
public class ProtocolPacketAuditService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolPacketAuditService.class);
    private static final int QUEUE_CAPACITY = 512;

    private final JdbcTemplate jdbcTemplate;
    private final LocalNetProperties properties;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean persistenceWarningLogged = new AtomicBoolean();
    private final AtomicBoolean queueWarningLogged = new AtomicBoolean();

    public ProtocolPacketAuditService(JdbcTemplate jdbcTemplate, LocalNetProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "protocol-packet-log-persist");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void record(LocalNetPacketRecord record) {
        if (!properties.isAuditEnabled() || record == null) {
            return;
        }
        try {
            executor.execute(() -> persist(record));
        } catch (RejectedExecutionException ex) {
            if (queueWarningLogged.compareAndSet(false, true)) {
                log.warn("Protocol packet audit queue full; subsequent packet audit writes may be skipped");
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void persist(LocalNetPacketRecord record) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO protocol_packet_log (
                      protocol_family, adapter_id, packet_direction, byte_length,
                      packet_summary, process_status, error_message, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                record.family().name(),
                record.adapterId(),
                record.direction().name(),
                record.byteLength(),
                trim(record.summary(), 1000),
                record.status(),
                trim(record.error(), 1000),
                Timestamp.from(record.recordedAt())
            );
        } catch (DataAccessException ex) {
            if (persistenceWarningLogged.compareAndSet(false, true)) {
                log.warn("Protocol packet audit persistence skipped after database write failure: {}", ex.getMessage());
            }
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
