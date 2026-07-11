package com.railwaysim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class ApiOperationLogServiceTests {

    @Test
    void recordReturnsBeforeSlowDatabaseAuditWrite() {
        JdbcTemplate slowDatabase = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                throw new DataAccessResourceFailureException("database unavailable");
            }
        };
        ApiOperationLogService service = new ApiOperationLogService(slowDatabase, new ObjectMapper());

        long started = System.nanoTime();
        service.record("vehicle-runtime", "TRAIN_RUNTIME_REGISTER", "train:TR-TEST", "before", "after", "test", "trace");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // 控制接口先返回，但异步失败必须进入可观察 FAILED 状态，不能伪报成功。
        assertThat(elapsedMillis).isLessThan(300);
        assertThat(service.entries()).hasSize(1);
        assertThat(service.awaitIdle(Duration.ofSeconds(3))).isTrue();
        assertThat(service.totalFailed()).isEqualTo(1);
        assertThat(service.entries().get(0).status()).isEqualTo("FAILED");
        service.shutdown();
    }

    @Test
    void recordPersistsRealRowInH2MySqlMode() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:operation-log;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE operation_log (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              operator_name VARCHAR(64) NOT NULL,
              operation_type VARCHAR(64) NOT NULL,
              target_ref VARCHAR(128) NOT NULL,
              detail_json JSON,
              run_id VARCHAR(64),
              tick BIGINT,
              trace_id VARCHAR(64),
              before_state VARCHAR(1024),
              after_state VARCHAR(1024),
              reason VARCHAR(512),
              status VARCHAR(32) NOT NULL,
              retry_count INT NOT NULL,
              error_text VARCHAR(1024),
              created_at TIMESTAMP NOT NULL
            )
            """);
        ApiOperationLogService service = new ApiOperationLogService(jdbcTemplate, new ObjectMapper());

        service.recordWithRunId(
            "tester", "POWER_FAULT_INJECT", "power-section:P01",
            "ENERGIZED", "DEENERGIZED", "test", "trace-1", "run-1", 42
        );

        assertThat(service.awaitIdle(Duration.ofSeconds(2))).isTrue();
        assertThat(service.totalPersisted()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operation_log", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT run_id FROM operation_log", String.class)).isEqualTo("run-1");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM operation_log", String.class)).isEqualTo("PERSISTED");
        service.shutdown();
    }
}
