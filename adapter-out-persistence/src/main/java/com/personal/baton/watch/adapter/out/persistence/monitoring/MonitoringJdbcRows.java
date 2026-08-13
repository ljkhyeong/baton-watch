package com.personal.baton.watch.adapter.out.persistence.monitoring;

import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Shared SQL projection and mapping for the current monitor row. */
final class MonitoringJdbcRows {

    static final String MONITOR_COLUMNS = """
            resource_reference,
            source_revision,
            monitor_status,
            target_url,
            current_health,
            consecutive_failures,
            last_outcome,
            last_checked_at,
            last_conclusive_at,
            next_check_at,
            lease_token,
            lease_attempt_id
            """;

    private MonitoringJdbcRows() {
    }

    static MonitorRow mapMonitor(ResultSet resultSet, int ignoredRow) throws SQLException {
        return new MonitorRow(
                resultSet.getString("resource_reference"),
                new SourceRevision(resultSet.getLong("source_revision")),
                MonitoringState.valueOf(resultSet.getString("monitor_status")),
                resultSet.getString("target_url"),
                Health.valueOf(resultSet.getString("current_health")),
                resultSet.getInt("consecutive_failures"),
                enumValue(CheckOutcome.class, resultSet.getString("last_outcome")),
                instant(resultSet, "last_checked_at"),
                instant(resultSet, "last_conclusive_at"),
                instant(resultSet, "next_check_at"),
                resultSet.getObject("lease_token", UUID.class),
                resultSet.getObject("lease_attempt_id", UUID.class));
    }

    static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime databaseTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    record MonitorRow(
            String resourceReference,
            SourceRevision sourceRevision,
            MonitoringState monitoringState,
            String targetUrl,
            Health health,
            int consecutiveFailures,
            CheckOutcome lastOutcome,
            Instant lastCheckedAt,
            Instant lastConclusiveAt,
            Instant nextCheckAt,
            UUID leaseToken,
            UUID leaseAttemptId) {

        HealthDerivation derivation() {
            return new HealthDerivation(health, consecutiveFailures);
        }
    }
}
