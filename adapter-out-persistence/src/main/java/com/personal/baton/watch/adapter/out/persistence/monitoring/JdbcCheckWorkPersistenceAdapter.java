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
import com.personal.baton.watch.application.monitoring.service.TimeBoundaryPolicy;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.HealthDerivationPolicy;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.Assert;

/** 점검 선점, 완료 처리, 제한된 시도 보존을 담당하는 JDBC 어댑터다. */
public final class JdbcCheckWorkPersistenceAdapter implements CheckWorkPersistencePort {

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final HealthDerivationPolicy healthPolicy;
    private final JdbcHealthChangeEventAppender eventAppender;

    public JdbcCheckWorkPersistenceAdapter(
            JdbcClient jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.healthPolicy = new HealthDerivationPolicy();
        this.eventAppender = new JdbcHealthChangeEventAppender(jdbc);
    }

    @Override
    public Optional<ClaimedCheck> claimDueCheck(Duration leaseDuration) {
        Duration supportedLease = TimeBoundaryPolicy.requireSupportedOffset(
                leaseDuration, "leaseDuration");
        return transactions.execute(ignored -> {
            Instant claimedAt = jdbc.sql("SELECT transaction_timestamp()")
                    .query(OffsetDateTime.class)
                    .single()
                    .toInstant();
            Instant leaseUntil = claimedAt.plus(supportedLease);
            return claimInTransaction(claimedAt, leaseUntil);
        });
    }

    @Override
    public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
        Objects.requireNonNull(finalization, "finalization");
        return transactions.execute(ignored -> finalizeInTransaction(finalization));
    }

    @Override
    public int purgeAttempts(Instant completedBefore, int limit) {
        Objects.requireNonNull(completedBefore, "completedBefore");
        Assert.isTrue(limit > 0, "limit must be positive");
        return transactions.execute(ignored -> jdbc.sql("""
                        WITH completed_candidates AS MATERIALIZED (
                            SELECT attempt.attempt_id, result.completed_at AS retention_at
                            FROM watch_result result
                            JOIN watch_attempt attempt ON attempt.attempt_id = result.attempt_id
                            WHERE result.completed_at < ?
                            ORDER BY result.completed_at, result.attempt_id
                            LIMIT ?
                            FOR UPDATE OF attempt SKIP LOCKED
                        ),
                        abandoned_candidates AS MATERIALIZED (
                            SELECT attempt.attempt_id, attempt.claimed_at AS retention_at
                            FROM watch_attempt attempt
                            WHERE attempt.claimed_at < ?
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM watch_result result
                                  WHERE result.attempt_id = attempt.attempt_id
                              )
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM watch_monitor monitor
                                  WHERE monitor.lease_attempt_id = attempt.attempt_id
                              )
                            ORDER BY attempt.claimed_at, attempt.attempt_id
                            LIMIT ?
                            FOR UPDATE OF attempt SKIP LOCKED
                        ),
                        candidates AS (
                            SELECT attempt_id, retention_at FROM completed_candidates
                            UNION ALL
                            SELECT attempt_id, retention_at FROM abandoned_candidates
                            ORDER BY retention_at, attempt_id
                            LIMIT ?
                        )
                        DELETE FROM watch_attempt attempt
                        USING candidates
                        WHERE attempt.attempt_id = candidates.attempt_id
                        """)
                .params(
                        databaseTime(completedBefore),
                        limit,
                        databaseTime(completedBefore),
                        limit,
                        limit)
                .update());
    }

    private Optional<ClaimedCheck> claimInTransaction(
            Instant claimedAt, Instant leaseUntil) {
        Optional<MonitorRow> due = jdbc.sql(
                        "SELECT " + MONITOR_COLUMNS + """
                                 FROM watch_monitor
                                 WHERE monitor_status = 'ACTIVE'
                                   AND next_check_at <= ?
                                   AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                                 ORDER BY next_check_at, resource_reference
                                 LIMIT 1
                                 FOR UPDATE SKIP LOCKED
                                """)
                .params(databaseTime(claimedAt), databaseTime(claimedAt))
                .query(MonitoringJdbcRows::mapMonitor)
                .optional();
        if (due.isEmpty()) {
            return Optional.empty();
        }
        MonitorRow monitor = due.orElseThrow();

        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO watch_attempt (
                            attempt_id,
                            resource_reference,
                            source_revision,
                            target_url,
                            lease_token,
                            claimed_at,
                            lease_expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        attemptId,
                        monitor.resourceReference(),
                        monitor.sourceRevision().value(),
                        monitor.targetUrl(),
                        leaseToken,
                        databaseTime(claimedAt),
                        databaseTime(leaseUntil))
                .update();
        jdbc.sql("""
                        UPDATE watch_monitor
                        SET lease_token = ?, lease_attempt_id = ?, lease_expires_at = ?, updated_at = ?
                        WHERE resource_reference = ?
                        """)
                .params(
                        leaseToken,
                        attemptId,
                        databaseTime(leaseUntil),
                        databaseTime(claimedAt),
                        monitor.resourceReference())
                .update();
        return Optional.of(new ClaimedCheck(
                attemptId,
                leaseToken,
                new TargetUrl(monitor.targetUrl()),
                monitor.nextCheckAt(),
                claimedAt,
                monitor.leaseAttemptId() != null
                        && monitor.leaseExpiresAt() != null
                        && !monitor.leaseExpiresAt().isAfter(claimedAt)));
    }

    private CheckFinalizationStatus finalizeInTransaction(
            CheckFinalization finalization) {
        if (resultExists(finalization.attemptId())) {
            return CheckFinalizationStatus.ALREADY_FINALIZED;
        }

        AttemptRow attempt = jdbc.sql("""
                        SELECT resource_reference, source_revision, lease_token, claimed_at
                        FROM watch_attempt
                        WHERE attempt_id = ?
                        """)
                .param(finalization.attemptId())
                .query(JdbcCheckWorkPersistenceAdapter::mapAttempt)
                .optional()
                .orElse(null);
        if (attempt == null || !attempt.leaseToken().equals(finalization.leaseToken())) {
            return CheckFinalizationStatus.STALE_CLAIM;
        }
        if (finalization.completedAt().isBefore(attempt.claimedAt())) {
            throw new IllegalArgumentException("completion cannot precede claim");
        }

        MonitorRow monitor = jdbc.sql(
                        "SELECT " + MONITOR_COLUMNS
                                + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE")
                .param(attempt.resourceReference())
                .query(MonitoringJdbcRows::mapMonitor)
                .single();

        if (resultExists(finalization.attemptId())) {
            return CheckFinalizationStatus.ALREADY_FINALIZED;
        }
        if (!monitorOwnsClaim(monitor, attempt, finalization)) {
            return CheckFinalizationStatus.STALE_CLAIM;
        }

        CheckObservation observation = finalization.observation();
        jdbc.sql("""
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
                        """)
                .params(
                        finalization.attemptId(),
                        observation.outcome().name(),
                        observation.httpStatusCode(),
                        databaseTime(finalization.completedAt()),
                        observation.duration().getSeconds(),
                        observation.duration().getNano(),
                        observation.responseBytes(),
                        observation.redirectCount())
                .update();

        HealthDerivation derived = healthPolicy.derive(
                monitor.derivation(), observation.outcome());
        Instant lastConclusiveAt = observation.outcome().isConclusive()
                ? finalization.completedAt()
                : monitor.lastConclusiveAt();
        jdbc.sql("""
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
                        """)
                .params(
                        derived.health().name(),
                        derived.consecutiveFailures(),
                        observation.outcome().name(),
                        databaseTime(finalization.completedAt()),
                        databaseTime(lastConclusiveAt),
                        databaseTime(finalization.nextCheckAt()),
                        databaseTime(finalization.completedAt()),
                        attempt.resourceReference())
                .update();

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
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM watch_result WHERE attempt_id = ?)")
                .param(attemptId)
                .query(Boolean.class)
                .single();
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

    private record AttemptRow(
            String resourceReference,
            SourceRevision sourceRevision,
            UUID leaseToken,
            Instant claimedAt) {
    }
}
