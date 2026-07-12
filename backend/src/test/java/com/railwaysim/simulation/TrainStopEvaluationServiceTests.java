package com.railwaysim.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.StoppingControlProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.StoppingTargetOverriddenEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrainStopEvaluationServiceTests {
    private JdbcTemplate jdbc;
    private TrainStopEvaluationService service;
    private SimpleEventBus eventBus;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stop-result;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS train_stop_result");
        jdbc.execute("""
            CREATE TABLE train_stop_result (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, result_id VARCHAR(192) NOT NULL UNIQUE,
              simulation_run_id VARCHAR(64) NOT NULL, train_id VARCHAR(64) NOT NULL,
              station_id VARCHAR(64), platform_id VARCHAR(64), target_source VARCHAR(32) NOT NULL,
              target_position_meters DOUBLE NOT NULL, target_valid_from_tick BIGINT NOT NULL,
              target_overridden_by_ma BOOLEAN NOT NULL, actual_position_meters DOUBLE NOT NULL,
              signed_error_meters DOUBLE NOT NULL, absolute_error_meters DOUBLE NOT NULL,
              overrun BOOLEAN NOT NULL, success BOOLEAN NOT NULL, reason_code VARCHAR(128) NOT NULL,
              maximum_deceleration_mps2 DOUBLE NOT NULL, maximum_jerk_mps3 DOUBLE NOT NULL,
              brake_transition_count INT NOT NULL, emergency_brake BOOLEAN NOT NULL,
              final_control_stage VARCHAR(32) NOT NULL,
              control_stage_history_json JSON NOT NULL,
              control_mode VARCHAR(32), parameter_version VARCHAR(64) NOT NULL,
              stable_at_tick BIGINT NOT NULL, recorded_at TIMESTAMP NOT NULL
            )
            """);
        eventBus = new SimpleEventBus();
        service = new TrainStopEvaluationService(
            catalog(), jdbc, new StoppingControlProperties(), eventBus);
    }

    @Test
    void stableStopCreatesExactlyOnePersistedResult() {
        TickContext context = new TickContext(20, 100, 0.1, Instant.parse("2026-07-11T00:00:02Z"), "run-stop");
        TrainState stopped = stoppedTrain(1000.4, 2, "SERVICE");

        List<TrainStopResult> first = service.evaluate(context, List.of(stopped));
        List<TrainStopResult> duplicate = service.evaluate(
            new TickContext(21, 100, 0.1, context.simulatedTime().plusMillis(100), "run-stop"),
            List.of(stopped));

        assertThat(first).singleElement().satisfies(result -> {
            assertThat(result.signedErrorMeters()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(result.absoluteErrorMeters()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(result.success()).isTrue();
            assertThat(result.overrun()).isFalse();
            assertThat(result.platformId()).isEqualTo("P-S01");
        });
        assertThat(duplicate).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_stop_result", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT simulation_run_id FROM train_stop_result", String.class))
            .isEqualTo("run-stop");
    }

    @Test
    void stoppingEpisodeKeepsApproachMetricsAndMovementAuthorityTarget() {
        TickContext approachContext = new TickContext(
            10, 100, 0.1, Instant.parse("2026-07-11T00:00:01Z"), "run-ma");
        service.evaluate(approachContext, List.of(approachingTrain()));

        TrainStopResult result = service.evaluate(
            new TickContext(20, 100, 0.1, approachContext.simulatedTime().plusSeconds(1), "run-ma"),
            List.of(stoppedTrain(1000.4, 2, "SERVICE"))).get(0);

        assertThat(result.targetSource()).isEqualTo("MOVEMENT_AUTHORITY");
        assertThat(result.targetPositionMeters()).isCloseTo(1000.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.maximumDecelerationMetersPerSecondSquared()).isEqualTo(1.5);
        assertThat(result.brakeTransitionCount()).isEqualTo(1);
        assertThat(result.resultId()).endsWith(":10");
        assertThat(result.targetOverriddenByMovementAuthority()).isTrue();
        assertThat(result.controlStageHistory()).containsExactly("BRAKE_1", "HOLD");
        assertThat(eventBus.drain()).singleElement().isInstanceOfSatisfying(
            StoppingTargetOverriddenEvent.class,
            event -> {
                assertThat(event.reasonCode()).isEqualTo("TARGET_OVERRIDDEN_BY_MA");
                assertThat(event.selectedTarget().source()).isEqualTo("MOVEMENT_AUTHORITY");
                assertThat(event.tick()).isEqualTo(10);
            });
    }

    private StaticInfrastructureCatalog catalog() {
        OperationalLineData line = new OperationalLineData(
            "L1", "line", List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new OperationalLineData.StationDefinition("S01", "station", 1000, List.of("P-S01"))),
            List.of(), List.of(), List.of(), List.of());
        OperationalPowerData power = new OperationalPowerData(
            1500, 1200, 900, 2000, 2400, 0.01, true, "DISSIPATE",
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new StaticInfrastructureCatalog(line, power);
    }

    private TrainState stoppedTrain(double position, int dwellSeconds, String brakeState) {
        return new TrainState(
            "TR-001", "L1", "TR-001", position, 0.0, 118, position,
            Math.max(0, position - 118), 0.4, "DWELLING", "ATO", true,
            "CLOSED_LOCKED", "IDLE", brakeState, "NORMAL", true, true,
            "PASS", 0, "NORMAL", "GOOD", "STATION_STOPPED", "STATION_STOP_WINDOW",
            20, 1000, 0.4, 0, -0.2, 0, 1000, 0, 0, 0, 0, 0, 0,
            "OK", "S01", dwellSeconds, null);
    }

    private TrainState approachingTrain() {
        return new TrainState(
            "TR-001", "L1", "TR-001", 990, 3.0, 118, 990,
            872, 0.4, "RUNNING", "ATO", false,
            "CLOSED_LOCKED", "IDLE", "APPLIED", "NORMAL", true, true,
            "PASS", 0, "NORMAL", "GOOD", "MA_BRAKE", "MA_DISTANCE_LIMIT",
            20, 10, 50, 4.5, -1.5, 0, 1000, 0, 0, 0, 0, 0, 0,
            "OK", null, 0, null);
    }
}
