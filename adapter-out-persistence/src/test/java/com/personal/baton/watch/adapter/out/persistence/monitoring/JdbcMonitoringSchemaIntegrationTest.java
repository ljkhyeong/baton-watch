package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcMonitoringSchemaIntegrationTest extends PostgresPersistenceIntegrationTestSupport {

    @Test
    void migrationsCreateMetadataOnlyTablesWithDomainBounds() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "watch_attempt",
                "watch_health_change_event",
                "watch_monitor",
                "watch_result");
        assertThat(characterMaximum("watch_monitor", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_monitor", "target_url")).isEqualTo(TargetUrl.MAX_LENGTH);
        assertThat(characterMaximum("watch_attempt", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_attempt", "target_url")).isEqualTo(TargetUrl.MAX_LENGTH);

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
    void databaseRejectsMonitorStateAndHealthMismatches() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'UNKNOWN', 0, ?)
                """,
                "resource:inactive-with-next-check",
                databaseTime(BASE_TIME)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'HEALTHY', 1, NULL)
                """,
                "resource:healthy-with-failure"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int characterMaximum(String table, String column) {
        return jdbc.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
    }
}
