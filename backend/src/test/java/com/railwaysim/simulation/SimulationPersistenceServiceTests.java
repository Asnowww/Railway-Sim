package com.railwaysim.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SimulationPersistenceServiceTests {

    @Test
    void persistWritesTrainAndPowerSnapshots() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        createTables(jdbcTemplate);
        SimulationPersistenceService persistenceService = new SimulationPersistenceService(
            jdbcTemplate,
            new ObjectMapper()
        );

        persistenceService.persist(
            new TickContext(25, 200, 0.2, Instant.parse("2026-07-07T00:00:05Z")),
            List.of(trainState()),
            List.of(powerSectionState()),
            List.of(
                new FmuStepFailedEvent("TR-001", "HTTP 500", Instant.parse("2026-07-07T00:00:05Z")),
                new FmuFallbackActivatedEvent("fleet", "fallback", Instant.parse("2026-07-07T00:00:05Z"))
            )
        );

        assertThat(jdbcTemplate.queryForObject(
            "SELECT regen_brake_force_n FROM train_physics_snapshot WHERE train_id = 'TR-001'",
            Double.class
        )).isEqualTo(12_000.0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT energy_regenerated_kwh FROM train_energy_record WHERE train_id = 'TR-001'",
            Double.class
        )).isEqualTo(0.02);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT affected_train_ids_json FROM power_section_record WHERE section_id = 'P01'",
            String.class
        )).isEqualTo("[\"TR-001\"]");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fmu_fault_log WHERE tick = 25",
            Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT fallback_activated FROM fmu_fault_log WHERE fault_code = 'FMU_FALLBACK_ACTIVATED'",
            Boolean.class
        )).isTrue();
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:persistence-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            CREATE TABLE train_physics_snapshot (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              train_id VARCHAR(64) NOT NULL,
              tick BIGINT NOT NULL,
              position_meters DOUBLE NOT NULL,
              speed_mps DOUBLE NOT NULL,
              acceleration_mps2 DOUBLE NOT NULL,
              traction_force_n DOUBLE NOT NULL,
              brake_force_n DOUBLE NOT NULL,
              regen_brake_force_n DOUBLE NOT NULL DEFAULT 0,
              rail_current_a DOUBLE NOT NULL,
              traction_power_w DOUBLE NOT NULL,
              regen_power_w DOUBLE NOT NULL,
              fault_code VARCHAR(64) NOT NULL,
              data_quality VARCHAR(32) NOT NULL DEFAULT 'GOOD',
              recorded_at TIMESTAMP NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE train_energy_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              train_id VARCHAR(64) NOT NULL,
              tick BIGINT NOT NULL,
              energy_consumed_kwh DOUBLE NOT NULL,
              energy_regenerated_kwh DOUBLE NOT NULL,
              recorded_at TIMESTAMP NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE power_section_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              section_id VARCHAR(64) NOT NULL,
              tick BIGINT NOT NULL,
              voltage DOUBLE NOT NULL,
              current_value DOUBLE NOT NULL,
              status VARCHAR(32) NOT NULL,
              load_w DOUBLE NOT NULL DEFAULT 0,
              available_power_w DOUBLE NOT NULL DEFAULT 0,
              regen_power_w DOUBLE NOT NULL DEFAULT 0,
              absorbed_regen_power_w DOUBLE NOT NULL DEFAULT 0,
              unabsorbed_regen_power_w DOUBLE NOT NULL DEFAULT 0,
              breaker_status VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
              protection_state VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
              maintenance_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
              lockout_state VARCHAR(32) NOT NULL DEFAULT 'UNLOCKED',
              affected_train_ids_json VARCHAR(512),
              data_quality VARCHAR(32) NOT NULL DEFAULT 'GOOD',
              recorded_at TIMESTAMP NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE fmu_fault_log (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              tick BIGINT NOT NULL DEFAULT 0,
              train_id VARCHAR(64),
              fault_code VARCHAR(64) NOT NULL,
              detail_text VARCHAR(512) NOT NULL,
              fallback_activated BOOLEAN NOT NULL DEFAULT FALSE,
              created_at TIMESTAMP NOT NULL
            )
            """);
    }

    private TrainState trainState() {
        return new TrainState(
            "TR-001",
            "demo-line-1",
            "TR-001",
            500,
            12,
            120,
            500,
            380,
            0.35,
            "RUNNING",
            "ATO",
            false,
            "CLOSED_LOCKED",
            "TRACTION",
            "REGEN",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            0.4,
            80_000,
            20_000,
            12_000,
            500,
            375_000,
            100_000,
            0.08,
            0.02,
            "OK"
        );
    }

    private PowerSectionState powerSectionState() {
        return new PowerSectionState(
            "P01",
            "Power 01",
            0,
            1000,
            735,
            500,
            "ENERGIZED",
            375_000,
            100_000,
            80_000,
            20_000,
            2_500_000,
            "CLOSED",
            "NORMAL",
            "NONE",
            "UNLOCKED",
            List.of("TR-001"),
            "GOOD",
            Instant.parse("2026-07-07T00:00:05Z")
        );
    }
}
