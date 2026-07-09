package com.railwaysim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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

        // 控制接口必须先返回；数据库审计失败由异步线程降级处理。
        assertThat(elapsedMillis).isLessThan(300);
        assertThat(service.entries()).hasSize(1);
        service.shutdown();
    }
}
