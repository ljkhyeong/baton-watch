package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.MONITOR_COLUMNS;
import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.lockMonitor;

import com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.MonitorRow;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult.Status;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** 모니터 동기화, 프로젝션 조회, 오래된 프로젝션 처리를 담당하는 JDBC 어댑터다. */
public final class JdbcMonitorPersistenceAdapter implements MonitorPersistencePort {

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final JdbcHealthChangeEventAppender eventAppender;

    public JdbcMonitorPersistenceAdapter(
            JdbcClient jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.eventAppender = new JdbcHealthChangeEventAppender(jdbc);
    }

    @Override
    public SynchronizationResult synchronize(
            SynchronizeMonitorCommand command, Instant synchronizedAt) {
        return transactions.execute(
                ignored -> synchronizeInTransaction(command, synchronizedAt));
    }

    @Override
    public Optional<MonitorProjection> findProjection(ResourceReference resourceReference) {
        return jdbc.sql(
                        "SELECT " + MONITOR_COLUMNS
                                + " FROM watch_monitor WHERE resource_reference = ?")
                .param(resourceReference.value())
                .query(MonitoringJdbcRows::mapMonitor)
                .optional()
                .map(this::toProjection);
    }

    @Override
    public List<MonitorProjection> findProjections(List<ResourceReference> resourceReferences) {
        if (resourceReferences.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + MONITOR_COLUMNS
                        + " FROM watch_monitor WHERE resource_reference IN (:references)")
                .param("references", resourceReferences.stream().map(ResourceReference::value).toList())
                .query(MonitoringJdbcRows::mapMonitor)
                .list().stream()
                .map(this::toProjection)
                .toList();
    }

    @Override
    public MonitorCheckRequestResult requestCheck(
            ResourceReference resourceReference, Instant requestedAt, Duration minimumInterval) {
        return transactions.execute(ignored -> {
            MonitorRow monitor = lockMonitor(jdbc, resourceReference.value()).optional().orElse(null);
            if (monitor == null) {
                return new MonitorCheckRequestResult(Status.NOT_FOUND, null, 0);
            }
            return switch (toProjection(monitor).checkStatusAt(requestedAt)) {
                case INACTIVE -> new MonitorCheckRequestResult(Status.INACTIVE, null, 0);
                case IN_PROGRESS -> new MonitorCheckRequestResult(Status.IN_PROGRESS, null, 0);
                case QUEUED -> new MonitorCheckRequestResult(Status.ALREADY_SCHEDULED, monitor.nextCheckAt(), 0);
                case SCHEDULED -> advanceSchedule(
                        resourceReference, monitor.lastCheckRequestedAt(), requestedAt, minimumInterval);
            };
        });
    }

    /** 잠근 모니터 행에서 요청 간격을 확인하고 다음 점검을 요청 시각으로 당긴다. */
    private MonitorCheckRequestResult advanceSchedule(
            ResourceReference resourceReference,
            Instant lastRequestedAt,
            Instant requestedAt,
            Duration minimumInterval) {
        if (lastRequestedAt != null) {
            Instant retryAt = lastRequestedAt.plus(minimumInterval);
            if (retryAt.isAfter(requestedAt)) {
                Duration remaining = Duration.between(requestedAt, retryAt);
                long seconds = remaining.toSeconds() + (remaining.getNano() == 0 ? 0 : 1);
                return new MonitorCheckRequestResult(Status.RATE_LIMITED, null, seconds);
            }
        }
        jdbc.sql("""
                        UPDATE watch_monitor
                        SET next_check_at = ?, last_check_requested_at = ?
                        WHERE resource_reference = ?
                        """)
                .params(databaseTime(requestedAt), databaseTime(requestedAt), resourceReference.value())
                .update();
        return new MonitorCheckRequestResult(Status.SCHEDULED, requestedAt, 0);
    }

    @Override
    public int markStaleUnknown(Instant staleBefore, Instant markedAt, int limit) {
        return transactions.execute(
                ignored -> markStaleInTransaction(staleBefore, markedAt, limit));
    }

    private SynchronizationResult synchronizeInTransaction(
            SynchronizeMonitorCommand command, Instant synchronizedAt) {
        MonitorRow existing = lockMonitor(jdbc, command.resourceReference().value())
                .optional()
                .orElse(null);

        if (existing == null) {
            MonitorRow inserted = tryInsertMonitor(command, synchronizedAt);
            if (inserted != null) {
                return new SynchronizationResult(
                        SynchronizationStatus.APPLIED, toProjection(inserted));
            }
            existing = lockMonitor(jdbc, command.resourceReference().value()).single();
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
                ? new HealthDerivation(Health.UNKNOWN, 0)
                : existing.derivation();
        CheckOutcome lastOutcome = targetOrStateChanged ? null : existing.lastOutcome();
        Instant lastCheckedAt = targetOrStateChanged ? null : existing.lastCheckedAt();
        Instant lastConclusiveAt = targetOrStateChanged ? null : existing.lastConclusiveAt();
        Instant nextCheckAt = command.monitoringState() == MonitoringState.INACTIVE
                ? null
                : targetOrStateChanged ? synchronizedAt : existing.nextCheckAt();

        MonitorRow updated = jdbc.sql("""
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
                            lease_expires_at = NULL
                        WHERE resource_reference = ?
                        RETURNING
                        """ + MONITOR_COLUMNS)
                .params(
                        command.sourceRevision().value(),
                        command.monitoringState().name(),
                        requestedTarget,
                        derivation.health().name(),
                        derivation.consecutiveFailures(),
                        lastOutcome == null ? null : lastOutcome.name(),
                        databaseTime(lastCheckedAt),
                        databaseTime(lastConclusiveAt),
                        databaseTime(nextCheckAt),
                        command.resourceReference().value())
                .query(MonitoringJdbcRows::mapMonitor)
                .single();

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
        return jdbc.sql("""
                        INSERT INTO watch_monitor (
                            resource_reference,
                            source_revision,
                            monitor_status,
                            target_url,
                            current_health,
                            consecutive_failures,
                            next_check_at
                        ) VALUES (?, ?, ?, ?, 'UNKNOWN', 0, ?)
                        ON CONFLICT (resource_reference) DO NOTHING
                        RETURNING
                        """ + MONITOR_COLUMNS)
                .params(
                        command.resourceReference().value(),
                        command.sourceRevision().value(),
                        command.monitoringState().name(),
                        target,
                        databaseTime(nextCheckAt))
                .query(MonitoringJdbcRows::mapMonitor)
                .optional()
                .orElse(null);
    }

    private int markStaleInTransaction(
            Instant staleBefore, Instant markedAt, int limit) {
        List<MonitorRow> stale = jdbc.sql(
                        "SELECT " + MONITOR_COLUMNS + """
                                 FROM watch_monitor
                                 WHERE monitor_status = 'ACTIVE'
                                   AND current_health <> 'UNKNOWN'
                                   AND last_conclusive_at <= ?
                                 ORDER BY last_conclusive_at, resource_reference
                                 LIMIT ?
                                 FOR UPDATE SKIP LOCKED
                                """)
                .params(databaseTime(staleBefore), limit)
                .query(MonitoringJdbcRows::mapMonitor)
                .list();

        for (MonitorRow monitor : stale) {
            HealthDerivation markedStale = monitor.derivation().stale();
            jdbc.sql("""
                            UPDATE watch_monitor
                            SET current_health = ?, consecutive_failures = ?
                            WHERE resource_reference = ?
                            """)
                    .params(
                            markedStale.health().name(),
                            markedStale.consecutiveFailures(),
                            monitor.resourceReference())
                    .update();
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
                monitor.derivation(),
                Optional.ofNullable(monitor.lastOutcome()),
                Optional.ofNullable(monitor.lastCheckedAt()),
                Optional.ofNullable(monitor.lastConclusiveAt()),
                Optional.ofNullable(monitor.nextCheckAt()),
                Optional.ofNullable(monitor.leaseExpiresAt()));
    }

}
