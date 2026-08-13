package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.MONITOR_COLUMNS;
import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;

import com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.MonitorRow;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.HealthDerivationPolicy;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** 모니터 동기화, 프로젝션 조회, 오래된 프로젝션 처리를 담당하는 JDBC 어댑터다. */
public final class JdbcMonitorPersistenceAdapter implements MonitorPersistencePort {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final HealthDerivationPolicy healthPolicy;
    private final JdbcHealthChangeEventAppender eventAppender;

    public JdbcMonitorPersistenceAdapter(
            JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.healthPolicy = new HealthDerivationPolicy();
        this.eventAppender = new JdbcHealthChangeEventAppender(jdbc);
    }

    @Override
    public SynchronizationResult synchronize(
            SynchronizeMonitorCommand command, Instant synchronizedAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(synchronizedAt, "synchronizedAt");
        return transactions.execute(
                ignored -> synchronizeInTransaction(command, synchronizedAt));
    }

    @Override
    public Optional<MonitorProjection> findProjection(ResourceReference resourceReference) {
        Objects.requireNonNull(resourceReference, "resourceReference");
        List<MonitorRow> rows = jdbc.query(
                "SELECT " + MONITOR_COLUMNS + " FROM watch_monitor WHERE resource_reference = ?",
                MonitoringJdbcRows::mapMonitor,
                resourceReference.value());
        return DataAccessUtils.optionalResult(rows).map(this::toProjection);
    }

    @Override
    public int markStaleUnknown(Instant staleBefore, Instant markedAt, int limit) {
        Objects.requireNonNull(staleBefore, "staleBefore");
        Objects.requireNonNull(markedAt, "markedAt");
        requirePositiveLimit(limit);
        if (staleBefore.isAfter(markedAt)) {
            throw new IllegalArgumentException("stale cutoff cannot follow marked time");
        }
        return transactions.execute(
                ignored -> markStaleInTransaction(staleBefore, markedAt, limit));
    }

    private SynchronizationResult synchronizeInTransaction(
            SynchronizeMonitorCommand command, Instant synchronizedAt) {
        MonitorRow existing = DataAccessUtils.singleResult(jdbc.query(
                "SELECT " + MONITOR_COLUMNS
                        + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                MonitoringJdbcRows::mapMonitor,
                command.resourceReference().value()));

        if (existing == null) {
            MonitorRow inserted = tryInsertMonitor(command, synchronizedAt);
            if (inserted != null) {
                return new SynchronizationResult(
                        SynchronizationStatus.APPLIED, toProjection(inserted));
            }
            existing = DataAccessUtils.singleResult(jdbc.query(
                    "SELECT " + MONITOR_COLUMNS
                            + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                    MonitoringJdbcRows::mapMonitor,
                    command.resourceReference().value()));
            if (existing == null) {
                throw new IllegalStateException(
                        "conflicting monitor disappeared during synchronization");
            }
        }

        int revisionComparison = command.sourceRevision().compareTo(existing.sourceRevision());
        if (revisionComparison < 0) {
            return new SynchronizationResult(
                    SynchronizationStatus.STALE_REVISION, toProjection(existing));
        }

        String requestedTarget = command.targetUrl().map(TargetUrl::value).orElse(null);
        boolean samePayload = command.monitoringState() == existing.monitoringState()
                && Objects.equals(requestedTarget, existing.targetUrl());
        if (revisionComparison == 0) {
            SynchronizationStatus status = samePayload
                    ? SynchronizationStatus.UNCHANGED
                    : SynchronizationStatus.REVISION_CONFLICT;
            return new SynchronizationResult(status, toProjection(existing));
        }

        boolean targetOrStateChanged = !samePayload;
        HealthDerivation derivation = targetOrStateChanged
                ? HealthDerivation.unknown()
                : existing.derivation();
        CheckOutcome lastOutcome = targetOrStateChanged ? null : existing.lastOutcome();
        Instant lastCheckedAt = targetOrStateChanged ? null : existing.lastCheckedAt();
        Instant lastConclusiveAt = targetOrStateChanged ? null : existing.lastConclusiveAt();
        Instant nextCheckAt = command.monitoringState() == MonitoringState.INACTIVE
                ? null
                : targetOrStateChanged ? synchronizedAt : existing.nextCheckAt();

        MonitorRow updated = DataAccessUtils.requiredSingleResult(jdbc.query("""
                UPDATE watch_monitor
                SET source_revision = ?,
                    monitor_status = ?,
                    target_url = ?,
                    current_health = ?,
                    consecutive_failures = ?,
                    last_outcome = ?,
                    last_checked_at = ?,
                    last_conclusive_at = ?,
                    next_check_at = ?,
                    lease_token = NULL,
                    lease_attempt_id = NULL,
                    lease_expires_at = NULL,
                    updated_at = ?
                WHERE resource_reference = ?
                RETURNING
                """ + MONITOR_COLUMNS,
                MonitoringJdbcRows::mapMonitor,
                command.sourceRevision().value(),
                command.monitoringState().name(),
                requestedTarget,
                derivation.health().name(),
                derivation.consecutiveFailures(),
                lastOutcome == null ? null : lastOutcome.name(),
                databaseTime(lastCheckedAt),
                databaseTime(lastConclusiveAt),
                databaseTime(nextCheckAt),
                databaseTime(synchronizedAt),
                command.resourceReference().value()));

        if (existing.health() != derivation.health()) {
            eventAppender.append(
                    command.resourceReference().value(),
                    command.sourceRevision().value(),
                    null,
                    existing.health(),
                    derivation.health(),
                    synchronizedAt);
        }

        return new SynchronizationResult(
                SynchronizationStatus.APPLIED, toProjection(updated));
    }

    private MonitorRow tryInsertMonitor(
            SynchronizeMonitorCommand command, Instant synchronizedAt) {
        String target = command.targetUrl().map(TargetUrl::value).orElse(null);
        Instant nextCheckAt = command.monitoringState() == MonitoringState.ACTIVE
                ? synchronizedAt
                : null;
        return DataAccessUtils.singleResult(jdbc.query("""
                INSERT INTO watch_monitor (
                    resource_reference,
                    source_revision,
                    monitor_status,
                    target_url,
                    current_health,
                    consecutive_failures,
                    next_check_at,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, 'UNKNOWN', 0, ?, ?, ?)
                ON CONFLICT (resource_reference) DO NOTHING
                RETURNING
                """ + MONITOR_COLUMNS,
                MonitoringJdbcRows::mapMonitor,
                command.resourceReference().value(),
                command.sourceRevision().value(),
                command.monitoringState().name(),
                target,
                databaseTime(nextCheckAt),
                databaseTime(synchronizedAt),
                databaseTime(synchronizedAt)));
    }

    private int markStaleInTransaction(
            Instant staleBefore, Instant markedAt, int limit) {
        List<MonitorRow> stale = jdbc.query(
                "SELECT " + MONITOR_COLUMNS + """
                         FROM watch_monitor
                         WHERE monitor_status = 'ACTIVE'
                           AND current_health <> 'UNKNOWN'
                           AND last_conclusive_at <= ?
                         ORDER BY last_conclusive_at, resource_reference
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                        """,
                MonitoringJdbcRows::mapMonitor,
                databaseTime(staleBefore),
                limit);

        for (MonitorRow monitor : stale) {
            HealthDerivation markedStale = healthPolicy.markStale(monitor.derivation());
            jdbc.update("""
                    UPDATE watch_monitor
                    SET current_health = ?, consecutive_failures = ?, updated_at = ?
                    WHERE resource_reference = ?
                    """,
                    markedStale.health().name(),
                    markedStale.consecutiveFailures(),
                    databaseTime(markedAt),
                    monitor.resourceReference());
            eventAppender.append(
                    monitor.resourceReference(),
                    monitor.sourceRevision().value(),
                    null,
                    monitor.health(),
                    markedStale.health(),
                    markedAt);
        }
        return stale.size();
    }

    private MonitorProjection toProjection(MonitorRow monitor) {
        return new MonitorProjection(
                new ResourceReference(monitor.resourceReference()),
                monitor.sourceRevision(),
                monitor.monitoringState(),
                monitor.health(),
                monitor.consecutiveFailures(),
                Optional.ofNullable(monitor.lastOutcome()),
                Optional.ofNullable(monitor.lastCheckedAt()),
                Optional.ofNullable(monitor.nextCheckAt()));
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

}
