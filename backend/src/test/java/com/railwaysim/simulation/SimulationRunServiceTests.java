package com.railwaysim.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SimulationRunServiceTests {

    private JdbcTemplate jdbc;
    private SimulationRunService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:simulation-run;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS simulation_run");
        jdbc.execute("""
            CREATE TABLE simulation_run (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              run_id VARCHAR(64) NOT NULL UNIQUE,
              status VARCHAR(32) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              started_at TIMESTAMP NULL,
              ended_at TIMESTAMP NULL,
              last_tick BIGINT NOT NULL DEFAULT 0,
              end_reason VARCHAR(255)
            )
            """);
        service = new SimulationRunService(jdbc);
    }

    @Test
    void startPauseAndCompletePreserveOneRunTimeline() {
        Instant now = Instant.parse("2026-07-11T00:00:00Z");

        service.start("run-1", 0, now);
        service.pause("run-1", 12);
        service.start("run-1", 12, now.plusSeconds(2));
        service.complete("run-1", 20, now.plusSeconds(5), "STOPPED_BY_API");

        SimulationRunRecord record = service.find("run-1").orElseThrow();
        assertThat(record.status()).isEqualTo(SimulationRunStatus.COMPLETED);
        assertThat(record.startedAt()).isEqualTo(now);
        assertThat(record.endedAt()).isEqualTo(now.plusSeconds(5));
        assertThat(record.lastTick()).isEqualTo(20);
        assertThat(record.endReason()).isEqualTo("STOPPED_BY_API");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulation_run", Integer.class)).isEqualTo(1);
    }

    @Test
    void resetClosesOldRunAndCreatesIndependentNewRun() {
        Instant now = Instant.parse("2026-07-11T00:00:00Z");
        service.start("run-old", 0, now);
        service.rollover("run-old", "run-new", 8, now.plusSeconds(1), now.plusSeconds(1));

        assertThat(service.find("run-old").orElseThrow().status())
            .isEqualTo(SimulationRunStatus.CANCELLED_BY_RESET);
        assertThat(service.find("run-new").orElseThrow().status())
            .isEqualTo(SimulationRunStatus.CREATED);
        assertThat(service.list(10)).extracting(SimulationRunRecord::runId)
            .containsExactlyInAnyOrder("run-old", "run-new");
    }
}
