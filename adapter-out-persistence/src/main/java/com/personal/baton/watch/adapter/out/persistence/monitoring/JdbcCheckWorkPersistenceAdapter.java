package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.MONITOR_COLUMNS;
import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.instant;

import com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.MonitorRow;
import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.HealthDerivationPolicy;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** JDBC adapter for check claims, finalization, and bounded attempt retention. */
public final class JdbcCheckWorkPersistenceAdapter implements CheckWorkPersistencePort {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final HealthDerivationPolicy healthPolicy;
    private final JdbcHealthChangeEventAppender eventAppender;

    public JdbcCheckWorkPersistenceAdapter(
            JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.healthPolicy = new HealthDerivationPolicy();
        this.eventAppender = new JdbcHealthChangeEventAppender(jdbc);
    }

    @Override
    public List<ClaimedCheck> claimDueChecks(
            Instant claimedAt, Instant leaseUntil, int limit) {
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        requirePositiveLimit(limit);
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("lease must expire after it is claimed");
        }
        return requireTransactionResult(transactions.execute(
                ignored -> claimInTransaction(claimedAt, leaseUntil, limit)));
    }

    @Override
    public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
        Objects.requireNonNull(finalization, "finalization");
        return requireTransactionResult(
                transactions.execute(ignored -> finalizeInTransaction(finalization)));
    }

    @Override
    public int purgeAttempts(Instant completedBefore, int limit) {
        Objects.requireNonNull(completedBefore, "completedBefore");
        requirePositiveLimit(limit);
        return requireTransactionResult(transactions.execute(ignored -> jdbc.update("""
                DELETE FROM watch_attempt
                WHERE attempt_id IN (
                    SELECT attempt.attempt_id
                    FROM watch_attempt attempt
                    LEFT JOIN watch_result result ON result.attempt_id = attempt.attempt_id
                    WHERE (result.completed_at < ?)
                       OR (
                           result.attempt_id IS NULL
                           AND attempt.claimed_at < ?
                           AND NOT EXISTS (
                               SELECT 1
                               FROM watch_monitor monitor
                               WHERE monitor.lease_attempt_id = attempt.attempt_id
                           )
                       )
                    ORDER BY COALESCE(result.completed_at, attempt.claimed_at), attempt.attempt_id
                    LIMIT ?
                )
                """, databaseTime(completedBefore), databaseTime(completedBefore), limit)));
    }

    private List<ClaimedCheck> claimInTransaction(
            Instant claimedAt, Instant leaseUntil, int limit) {
        List<MonitorRow> due = jdbc.query(
                "SELECT " + MONITOR_COLUMNS + """
                         FROM watch_monitor
                         WHERE monitor_status = 'ACTIVE'
                           AND next_check_at <= ?
                           AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                         ORDER BY next_check_at, resource_reference
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                        """,
                MonitoringJdbcRows::mapMonitor,
                databaseTime(claimedAt),
                databaseTime(claimedAt),
                limit);

        return due.stream().map(monitor -> {
            UUID attemptId = UUID.randomUUID();
            UUID leaseToken = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO watch_attempt (
                        attempt_id,
                        resource_reference,
                        source_revision,
                        target_url,
                        lease_token,
                        claimed_at,
                        lease_expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    attemptId,
                    monitor.resourceReference(),
                    monitor.sourceRevision().value(),
                    monitor.targetUrl(),
                    leaseToken,
                    databaseTime(claimedAt),
                    databaseTime(leaseUntil));
            jdbc.update("""
                    UPDATE watch_monitor
                    SET lease_token = ?, lease_attempt_id = ?, lease_expires_at = ?, updated_at = ?
                    WHERE resource_reference = ?
                    """,
                    leaseToken,
                    attemptId,
                    databaseTime(leaseUntil),
                    databaseTime(claimedAt),
                    monitor.resourceReference());
            return new ClaimedCheck(
                    attemptId,
                    leaseToken,
                    new ResourceReference(monitor.resourceReference()),
                    monitor.sourceRevision(),
                    new TargetUrl(monitor.targetUrl()));
        }).toList();
    }

    private CheckFinalizationStatus finalizeInTransaction(
            CheckFinalization finalization) {
        if (resultExists(finalization.attemptId())) {
            return CheckFinalizationStatus.ALREADY_FINALIZED;
        }

        List<AttemptRow> attempts = jdbc.query("""
                SELECT attempt_id, resource_reference, source_revision, lease_token, claimed_at
                FROM watch_attempt
                WHERE attempt_id = ?
                """, JdbcCheckWorkPersistenceAdapter::mapAttempt, finalization.attemptId());
        if (attempts.isEmpty() || !attemptMatches(attempts.getFirst(), finalization)) {
            return CheckFinalizationStatus.STALE_CLAIM;
        }
        AttemptRow attempt = attempts.getFirst();
        if (finalization.completedAt().isBefore(attempt.claimedAt())) {
            throw new IllegalArgumentException("completion cannot precede claim");
        }

        List<MonitorRow> monitors = jdbc.query(
                "SELECT " + MONITOR_COLUMNS
                        + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                MonitoringJdbcRows::mapMonitor,
                finalization.resourceReference().value());

        if (resultExists(finalization.attemptId())) {
            return CheckFinalizationStatus.ALREADY_FINALIZED;
        }
        if (monitors.isEmpty() || !monitorOwnsClaim(monitors.getFirst(), finalization)) {
            return CheckFinalizationStatus.STALE_CLAIM;
        }

        MonitorRow monitor = monitors.getFirst();
        CheckObservation observation = finalization.observation();
        jdbc.update("""
                INSERT INTO watch_result (
                    attempt_id,
                    outcome,
                    http_status_code,
                    completed_at,
                    duration_seconds,
                    duration_nanos,
                    response_bytes,
                    redirect_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                finalization.attemptId(),
                observation.outcome().name(),
                observation.httpStatusCode(),
                databaseTime(finalization.completedAt()),
                observation.duration().getSeconds(),
                observation.duration().getNano(),
                observation.responseBytes(),
                observation.redirectCount());

        HealthDerivation derived = healthPolicy.derive(
                monitor.derivation(), observation.outcome());
        Instant lastConclusiveAt = observation.outcome().isConclusive()
                ? finalization.completedAt()
                : monitor.lastConclusiveAt();
        jdbc.update("""
                UPDATE watch_monitor
                SET current_health = ?,
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
                """,
                derived.health().name(),
                derived.consecutiveFailures(),
                observation.outcome().name(),
                databaseTime(finalization.completedAt()),
                databaseTime(lastConclusiveAt),
                databaseTime(finalization.nextCheckAt()),
                databaseTime(finalization.completedAt()),
                finalization.resourceReference().value());

        if (monitor.health() != derived.health()) {
            eventAppender.append(
                    finalization.resourceReference().value(),
                    finalization.sourceRevision().value(),
                    finalization.attemptId(),
                    monitor.health(),
                    derived.health(),
                    finalization.completedAt());
        }
        return CheckFinalizationStatus.APPLIED;
    }

    private boolean resultExists(UUID attemptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM watch_result WHERE attempt_id = ?)",
                Boolean.class,
                attemptId));
    }

    private boolean attemptMatches(
            AttemptRow attempt, CheckFinalization finalization) {
        return attempt.attemptId().equals(finalization.attemptId())
                && attempt.leaseToken().equals(finalization.leaseToken())
                && attempt.resourceReference().equals(
                        finalization.resourceReference().value())
                && attempt.sourceRevision().equals(finalization.sourceRevision());
    }

    private boolean monitorOwnsClaim(
            MonitorRow monitor, CheckFinalization finalization) {
        return monitor.monitoringState() == MonitoringState.ACTIVE
                && finalization.attemptId().equals(monitor.leaseAttemptId())
                && finalization.leaseToken().equals(monitor.leaseToken())
                && finalization.sourceRevision().equals(monitor.sourceRevision());
    }

    private static AttemptRow mapAttempt(
            ResultSet resultSet, int ignoredRow) throws SQLException {
        return new AttemptRow(
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getString("resource_reference"),
                new SourceRevision(resultSet.getLong("source_revision")),
                resultSet.getObject("lease_token", UUID.class),
                instant(resultSet, "claimed_at"));
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static <T> T requireTransactionResult(T result) {
        return Objects.requireNonNull(result, "transaction callback result");
    }

    private record AttemptRow(
            UUID attemptId,
            String resourceReference,
            SourceRevision sourceRevision,
            UUID leaseToken,
            Instant claimedAt) {
    }
}
