package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class JdbcMonitoringSchemaIntegrationTest extends PostgresPersistenceIntegrationTestSupport {

    @Test
    void migrationCreatesFourMetadataOnlyTablesWithDomainBounds() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "watch_attempt", "watch_health_change_event", "watch_monitor", "watch_result");
        assertThat(characterMaximum("watch_monitor", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_monitor", "target_url")).isEqualTo(2048);
        assertThat(characterMaximum("watch_attempt", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_attempt", "target_url")).isEqualTo(2048);

        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                """, String.class);
        assertThat(columns)
                .noneMatch(name -> name.contains("body"))
                .noneMatch(name -> name.contains("resolved"))
                .noneMatch(name -> name.contains("exception"))
                .noneMatch(name -> name.contains("header"))
                .noneMatch(name -> name.contains("cookie"));
    }

    @Test
    void v2MigrationMakesExistingOutboxEventsPendingAndImmediatelyDue() {
        Flyway versionOne = Flyway.configure()
                .dataSource(testDataSource)
                .cleanDisabled(false)
                .target("1")
                .load();
        versionOne.clean();
        versionOne.migrate();

        jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at, created_at, updated_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'UNKNOWN', 0, NULL, ?, ?)
                """,
                "resource:migration",
                databaseTime(BASE_TIME),
                databaseTime(BASE_TIME));
        UUID eventId = UUID.randomUUID();
        Instant changedAt = BASE_TIME.plusSeconds(1);
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at
                ) VALUES (?, ?, 1, NULL, 'HEALTHY', 'UNKNOWN', ?)
                """,
                eventId,
                "resource:migration",
                databaseTime(changedAt));

        Flyway.configure().dataSource(testDataSource).load().migrate();

        assertThat(jdbc.queryForMap(
                        "SELECT delivery_status, delivery_attempt, next_attempt_at FROM watch_health_change_event WHERE event_id = ?",
                        eventId))
                .containsEntry("delivery_status", "PENDING")
                .containsEntry("delivery_attempt", 0);
        assertThat(jdbc.queryForObject(
                        "SELECT next_attempt_at FROM watch_health_change_event WHERE event_id = ?",
                        OffsetDateTime.class,
                        eventId)
                .toInstant()).isEqualTo(changedAt);
    }

    private int characterMaximum(String table, String column) {
        return jdbc.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
    }
}
