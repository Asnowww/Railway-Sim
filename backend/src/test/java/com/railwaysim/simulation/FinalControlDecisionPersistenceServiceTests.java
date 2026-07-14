package com.railwaysim.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.runtime.VehicleRuntimeTrainStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FinalControlDecisionPersistenceServiceTests {

    @Test
    void maps9300FinalReportAndKeepsQuerySummaryIdempotentAcrossRestart() throws Exception {
        JdbcTemplate jdbc = jdbc("decision-evidence");
        createSchema(jdbc);
        jdbc.update("""
            INSERT INTO simulation_run (run_id, status, created_at, last_tick)
            VALUES ('run-001', 'RUNNING', CURRENT_TIMESTAMP, 42)
            """);
        TickContext context = new TickContext(42, 100, 0.1, Instant.now(), "run-001");
        VehicleRuntimeTrainStep step = new VehicleRuntimeTrainStep("TR-001", null, report());

        FinalControlDecisionPersistenceService first = service(jdbc, 8);
        assertThat(first.persistFinalControlDecisions(context, List.of(step))).isTrue();
        assertThat(first.persistFinalControlDecisions(context, List.of(step))).isTrue();
        assertThat(first.awaitIdle(Duration.ofSeconds(2))).isTrue();
        first.close();

        FinalControlDecisionPersistenceService restarted = service(jdbc, 8);
        assertThat(restarted.persistFinalControlDecisions(context, List.of(step))).isTrue();
        assertThat(restarted.awaitIdle(Duration.ofSeconds(2))).isTrue();

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM vehicle_control_command_log WHERE simulation_run_id = 'run-001'",
            Long.class
        )).isEqualTo(1L);
        Map<String, Object> stored = jdbc.queryForMap(
            "SELECT * FROM vehicle_control_command_log WHERE simulation_run_id = 'run-001'"
        );
        assertThat(stored)
            .containsEntry("COMMAND_ID", "CMD-42")
            .containsEntry("TRACE_ID", "TRACE-42")
            .containsEntry("OPERATION_MODE", "ATO")
            .containsEntry("DECISION_SOURCE", "DRIVER")
            .containsEntry("REASON_CODE", "DRIVER_COMMAND_SELECTED");

        SimulationRunService runService = new SimulationRunService(jdbc);
        assertThat(runService.controlDecisions("run-001", 10, 0)).hasSize(1);
        assertThat(runService.summary("run-001").get("controlDecisionCount")).isEqualTo(1L);
        restarted.close();
    }

    @Test
    void boundsBacklogWhileDatabaseIsDisconnectedAndDrainsAfterRecovery() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CountDownLatch writeStarted = new CountDownLatch(1);
        AtomicBoolean databaseRecovered = new AtomicBoolean();
        doAnswer(invocation -> {
            writeStarted.countDown();
            if (!databaseRecovered.get()) {
                throw new DataAccessResourceFailureException("database disconnected");
            }
            return new int[] {1};
        }).when(jdbc).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
        FinalControlDecisionPersistenceService service = service(jdbc, 1);
        VehicleRuntimeTrainStep step = new VehicleRuntimeTrainStep("TR-001", null, report());

        assertThat(service.persistFinalControlDecisions(context(1), List.of(step))).isTrue();
        assertThat(writeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(service.persistFinalControlDecisions(context(2), List.of(step))).isTrue();
        assertThat(service.persistFinalControlDecisions(context(3), List.of(step))).isFalse();

        databaseRecovered.set(true);
        assertThat(service.awaitIdle(Duration.ofSeconds(2))).isTrue();
        service.close();
    }

    private FinalControlDecisionPersistenceService service(JdbcTemplate jdbc, int capacity) {
        SimulationProperties properties = new SimulationProperties();
        properties.setControlDecisionQueueCapacity(capacity);
        return new FinalControlDecisionPersistenceService(jdbc, properties);
    }

    private TickContext context(long tick) {
        return new TickContext(tick, 100, 0.1, Instant.now(), "run-001");
    }

    private JdbcTemplate jdbc(String databaseName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""
        );
        return new JdbcTemplate(dataSource);
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE simulation_run (
                run_id VARCHAR(64) PRIMARY KEY, status VARCHAR(32), created_at TIMESTAMP,
                started_at TIMESTAMP, ended_at TIMESTAMP, last_tick BIGINT, end_reason VARCHAR(255)
            )
            """);
        jdbc.execute("""
            CREATE TABLE vehicle_control_command_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, simulation_run_id VARCHAR(64) NOT NULL,
                tick BIGINT NOT NULL, train_id VARCHAR(64) NOT NULL, command_id VARCHAR(128),
                trace_id VARCHAR(128), operation_mode VARCHAR(32), decision_source VARCHAR(64),
                traction_command DOUBLE NOT NULL, brake_command DOUBLE NOT NULL,
                emergency_brake BOOLEAN NOT NULL, reason_code VARCHAR(128), decided_at TIMESTAMP NOT NULL,
                UNIQUE (simulation_run_id, tick, train_id)
            )
            """);
        for (String table : List.of(
            "train_physics_snapshot", "power_section_record", "train_stop_result", "power_vehicle_fault_record"
        )) {
            jdbc.execute("CREATE TABLE " + table + " (simulation_run_id VARCHAR(64))");
        }
    }

    private TrainStateReport report() {
        return new TrainStateReport(
            "TR-001", "ATO", true, "CLOSED_LOCKED", "APPLYING", "RELEASED", "NORMAL",
            true, true, "PASS", 0, "NORMAL", "GOOD", 42_000, "NORMAL", 4, 4, "NONE",
            "ACCELERATING", "LEGACY_CONSTRAINT", 22.2, 1000, 500, 100,
            0.65, 0.0, false, 750, 1_000_000, "OK", "DRIVER", "CMD-42", "TRACE-42",
            "DRIVER_COMMAND_SELECTED"
        );
    }
}
