package com.railwaysim.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AlarmLifecycleServiceTests {
    private JdbcTemplate jdbc;
    private AlarmLifecycleService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:alarm-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS alarm_record");
        jdbc.execute("""
            CREATE TABLE alarm_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, alarm_id VARCHAR(255) NOT NULL UNIQUE,
              simulation_run_id VARCHAR(64) NOT NULL, alarm_code VARCHAR(192) NOT NULL,
              source_module VARCHAR(64) NOT NULL, location_ref VARCHAR(128) NOT NULL,
              level TINYINT NOT NULL, title VARCHAR(128) NOT NULL, detail_text VARCHAR(512) NOT NULL,
              state VARCHAR(32) NOT NULL, confirmed BOOLEAN NOT NULL,
              raised_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP NOT NULL,
              acknowledged_at TIMESTAMP NULL, acknowledged_by VARCHAR(64), cleared_at TIMESTAMP NULL,
              affected_train_ids_json JSON, affected_section_ids_json JSON,
              safety_action VARCHAR(128), clear_condition VARCHAR(255), recovery_condition VARCHAR(255)
            )
            """);
        service = new AlarmLifecycleService(jdbc);
    }

    @Test
    void sameFaultKeepsStableIdAndSupportsAcknowledgeAndClear() {
        Instant firstSeen = Instant.parse("2026-07-11T00:00:00Z");
        Alarm candidate = new Alarm(
            "POWER_STATE:P01:DEENERGIZED", "power", "P01", 3,
            "power fault", "deenergized", firstSeen, false);

        String id = service.reconcile("run-alarm", List.of(candidate), firstSeen).get(0).id();
        List<Alarm> second = service.reconcile("run-alarm", List.of(candidate), firstSeen.plusSeconds(1));
        AlarmRecord acknowledged = service.acknowledge(id, "operator-a", firstSeen.plusSeconds(2));
        service.reconcile("run-alarm", List.of(), firstSeen.plusSeconds(3));

        assertThat(second).singleElement().satisfies(alarm -> assertThat(alarm.id()).isEqualTo(id));
        assertThat(acknowledged.state()).isEqualTo(AlarmLifecycleState.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedBy()).isEqualTo("operator-a");
        assertThat(service.records("run-alarm")).singleElement().satisfies(record -> {
            assertThat(record.state()).isEqualTo(AlarmLifecycleState.CLEARED);
            assertThat(record.clearedAt()).isEqualTo(firstSeen.plusSeconds(3));
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM alarm_record", String.class)).isEqualTo("CLEARED");
        assertThat(service.records("run-alarm").get(0).impact().affectedSectionIds())
            .containsExactly("P01");
        assertThat(service.records("run-alarm").get(0).impact().safetyAction())
            .isEqualTo("POWER_DERATE_OR_TRACTION_CUTOFF");
    }

    @Test
    void recurringFaultAfterClearCreatesNewOccurrence() {
        Instant now = Instant.parse("2026-07-11T00:00:00Z");
        Alarm candidate = new Alarm("TRAIN_FAULT:TR-1:X", "train", "TR-1", 2, "fault", "x", now, false);

        String first = service.reconcile("run-alarm", List.of(candidate), now).get(0).id();
        service.reconcile("run-alarm", List.of(), now.plusSeconds(1));
        String second = service.reconcile("run-alarm", List.of(candidate), now.plusSeconds(2)).get(0).id();

        assertThat(second).isNotEqualTo(first);
        assertThat(service.records("run-alarm")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_record", Integer.class)).isEqualTo(2);
    }

    @Test
    void persistedAlarmHistoryAndActiveOccurrenceAreRestoredAfterRestart() {
        Instant now = Instant.parse("2026-07-11T00:00:00Z");
        Alarm candidate = new Alarm(
            "TRAIN_FAULT:TR-1:X", "train", "TR-1", 2, "fault", "x", now, false);
        String id = service.reconcile("run-restart", List.of(candidate), now).get(0).id();

        AlarmLifecycleService restarted = new AlarmLifecycleService(jdbc);
        restarted.loadPersistedRecords();

        assertThat(restarted.records("run-restart")).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo(id);
            assertThat(record.impact().affectedTrainIds()).containsExactly("TR-1");
        });
        assertThat(restarted.reconcile("run-restart", List.of(candidate), now.plusSeconds(1)))
            .singleElement().satisfies(alarm -> assertThat(alarm.id()).isEqualTo(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_record", Integer.class))
            .isEqualTo(1);
    }
}
