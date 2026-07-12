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
        jdbc.execute("DROP TABLE IF EXISTS train_stop_result");
        jdbc.execute("""
            CREATE TABLE train_stop_result (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              simulation_run_id VARCHAR(64) NOT NULL,
              train_id VARCHAR(64) NOT NULL,
              station_id VARCHAR(64),
              absolute_error_meters DOUBLE NOT NULL,
              success BOOLEAN NOT NULL,
              overrun BOOLEAN NOT NULL,
              emergency_brake BOOLEAN NOT NULL,
              reason_code VARCHAR(128) NOT NULL,
              stable_at_tick BIGINT NOT NULL
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

    @Test
    void stopStatisticsUsesScenarioFiltersAndNearestRankP95() {
        for (int index = 0; index < 10; index++) {
            double error = (index + 1) / 10.0;
            jdbc.update("""
                INSERT INTO train_stop_result (
                  simulation_run_id, train_id, station_id, absolute_error_meters,
                  success, overrun, emergency_brake, reason_code, stable_at_tick
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "run-stats", "TR-001", "S01", error, index < 9, index == 9,
                false, index < 9 ? "STOP_SUCCESS" : "STOP_OVERRUN", index);
        }
        jdbc.update("""
            INSERT INTO train_stop_result (
              simulation_run_id, train_id, station_id, absolute_error_meters,
              success, overrun, emergency_brake, reason_code, stable_at_tick
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, "run-stats", "TR-002", "S02", 9.0, false, true, true,
            "EMERGENCY_BRAKE_USED", 20);

        TrainStopStatistics statistics = service.stopStatistics(
            "run-stats", "TR-001", "S01", 10);

        assertThat(statistics.sampleCount()).isEqualTo(10);
        assertThat(statistics.sampleRequirementMet()).isTrue();
        assertThat(statistics.meanAbsoluteErrorMeters()).isCloseTo(
            0.55, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(statistics.p95AbsoluteErrorMeters()).isEqualTo(1.0);
        assertThat(statistics.varianceMetersSquared()).isCloseTo(
            0.0825, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(statistics.successRate()).isEqualTo(0.9);
        assertThat(statistics.reasonDistribution())
            .containsEntry("STOP_SUCCESS", 9)
            .containsEntry("STOP_OVERRUN", 1);
    }
}
