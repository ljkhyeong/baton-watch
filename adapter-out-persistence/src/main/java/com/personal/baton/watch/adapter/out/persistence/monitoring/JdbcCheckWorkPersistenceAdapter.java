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
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** 점검 선점, 완료 처리, 제한된 시도 보존을 담당하는 JDBC 어댑터다. */
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
        return transactions.execute(
                ignored -> claimInTransaction(claimedAt, leaseUntil, limit));
    }

    @Override
    public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
        Objects.requireNonNull(finalization, "finalization");
        return transactions.execute(ignored -> finalizeInTransaction(finalization));
    }

    @Override
    public int purgeAttempts(Instant completedBefore, int limit) {
        Objects.requireNonNull(completedBefore, "completedBefore");
        requirePositiveLimit(limit);
        return transactions.execute(ignored -> jdbc.update("""
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
                """, databaseTime(completedBefore), databaseTime(completedBefore), limit));
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

        List<ClaimedCheck> claimed = new ArrayList<>(due.size());
        for (MonitorRow monitor : due) {
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
            claimed.add(new ClaimedCheck(
                    attemptId,
                    leaseToken,
                    new TargetUrl(monitor.targetUrl())));
        }
        return claimed;
    }

    private CheckFinalizationStatus finalizeInTransaction(
            CheckFinalization finalization) {
        if (resultExists(finalization.attemptId())) {
            return CheckFinalizationStatus.ALREADY_FINALIZED;
        }

        AttemptRow attempt = DataAccessUtils.singleResult(jdbc.query("""
                SELECT resource_reference, source_revision, lease_token, claimed_at
                FROM watch_attempt
                WHERE attempt_id = ?
                """, JdbcCheckWorkPersistenceAdapter::mapAttempt, finalization.attemptId()));
        if (attempt == null || !attempt.leaseToken().equals(finalization.leaseToken())) {
            return CheckFinalizationStatus.STALE_CLAIM;
        }
        if (finalization.completedAt().isBefore(attempt.claimedAt())) {
            throw new IllegalArgumentException("completion cannot precede claim");
        }

        MonitorRow monitor = DataAccessUtils.singleResult(jdbc.query(
                "SELECT " + MONITOR_COLUMNS
                        + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                MonitoringJdbcRows::mapMonitor,
                attempt.resourceReference()));

        if (resultExists(finalization.attemptId())) {
            return CheckFinalizationStatus.ALREADY_FINALIZED;
        }
        if (monitor == null || !monitorOwnsClaim(monitor, attempt, finalization)) {
            return CheckFinalizationStatus.STALE_CLAIM;
        }

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
                attempt.resourceReference());

        if (monitor.health() != derived.health()) {
            eventAppender.append(
                    attempt.resourceReference(),
                    attempt.sourceRevision().value(),
                    finalization.attemptId(),
                    monitor.health(),
                    derived.health(),
                    finalization.completedAt());
        }
        return CheckFinalizationStatus.APPLIED;
    }

    private boolean resultExists(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM watch_result WHERE attempt_id = ?)",
                Boolean.class,
                attemptId);
    }

    private boolean monitorOwnsClaim(
            MonitorRow monitor, AttemptRow attempt, CheckFinalization finalization) {
        return monitor.monitoringState() == MonitoringState.ACTIVE
                && finalization.attemptId().equals(monitor.leaseAttemptId())
                && finalization.leaseToken().equals(monitor.leaseToken())
                && attempt.sourceRevision().equals(monitor.sourceRevision());
    }

    private static AttemptRow mapAttempt(
            ResultSet resultSet, int ignoredRow) throws SQLException {
        return new AttemptRow(
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

    private record AttemptRow(
            String resourceReference,
            SourceRevision sourceRevision,
            UUID leaseToken,
            Instant claimedAt) {
    }
}
