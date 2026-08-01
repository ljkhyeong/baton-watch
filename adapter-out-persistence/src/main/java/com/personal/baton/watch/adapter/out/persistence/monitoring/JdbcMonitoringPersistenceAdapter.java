package com.personal.baton.watch.adapter.out.persistence.monitoring;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationResult;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.HealthDerivationPolicy;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcMonitoringPersistenceAdapter implements MonitorPersistencePort, CheckWorkPersistencePort {

    private static final String MONITOR_COLUMNS = """
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
            lease_attempt_id,
            lease_expires_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final HealthDerivationPolicy healthPolicy;

    public JdbcMonitoringPersistenceAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.healthPolicy = new HealthDerivationPolicy();
    }

    @Override
    public SynchronizationResult synchronize(SynchronizeMonitorCommand command, Instant synchronizedAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(synchronizedAt, "synchronizedAt");
        return requireTransactionResult(transactions.execute(ignored -> synchronizeInTransaction(command, synchronizedAt)));
    }

    @Override
    public Optional<MonitorProjection> findProjection(ResourceReference resourceReference) {
        Objects.requireNonNull(resourceReference, "resourceReference");
        List<MonitorRow> rows = jdbc.query(
                "SELECT " + MONITOR_COLUMNS + " FROM watch_monitor WHERE resource_reference = ?",
                JdbcMonitoringPersistenceAdapter::mapMonitor,
                resourceReference.value());
        return rows.stream().findFirst().map(this::toProjection);
    }

    @Override
    public int markStaleUnknown(Instant staleBefore, Instant markedAt, int limit) {
        Objects.requireNonNull(staleBefore, "staleBefore");
        Objects.requireNonNull(markedAt, "markedAt");
        requirePositiveLimit(limit);
        if (staleBefore.isAfter(markedAt)) {
            throw new IllegalArgumentException("stale cutoff cannot follow marked time");
        }
        return requireTransactionResult(transactions.execute(
                ignored -> markStaleInTransaction(staleBefore, markedAt, limit)));
    }

    @Override
    public List<ClaimedCheck> claimDueChecks(Instant claimedAt, Instant leaseUntil, int limit) {
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        requirePositiveLimit(limit);
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("lease must expire after it is claimed");
        }
        return List.copyOf(requireTransactionResult(transactions.execute(
                ignored -> claimInTransaction(claimedAt, leaseUntil, limit))));
    }

    @Override
    public CheckFinalizationResult finalizeCheck(CheckFinalization finalization) {
        Objects.requireNonNull(finalization, "finalization");
        return requireTransactionResult(transactions.execute(ignored -> finalizeInTransaction(finalization)));
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

    private SynchronizationResult synchronizeInTransaction(
            SynchronizeMonitorCommand command, Instant synchronizedAt) {
        List<MonitorRow> existingRows = jdbc.query(
                "SELECT " + MONITOR_COLUMNS
                        + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                JdbcMonitoringPersistenceAdapter::mapMonitor,
                command.resourceReference().value());

        if (existingRows.isEmpty()) {
            MonitorRow inserted = tryInsertMonitor(command, synchronizedAt);
            if (inserted != null) {
                return new SynchronizationResult(SynchronizationStatus.APPLIED, toProjection(inserted));
            }
            existingRows = jdbc.query(
                    "SELECT " + MONITOR_COLUMNS
                            + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                    JdbcMonitoringPersistenceAdapter::mapMonitor,
                    command.resourceReference().value());
            if (existingRows.isEmpty()) {
                throw new IllegalStateException("conflicting monitor disappeared during synchronization");
            }
        }

        MonitorRow existing = existingRows.getFirst();
        int revisionComparison = command.sourceRevision().compareTo(existing.sourceRevision());
        if (revisionComparison < 0) {
            return new SynchronizationResult(SynchronizationStatus.STALE_REVISION, toProjection(existing));
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

        jdbc.update("""
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
                """,
                command.sourceRevision().value(),
                command.monitoringState().name(),
                requestedTarget,
                derivation.health().name(),
                derivation.consecutiveFailures(),
                enumName(lastOutcome),
                databaseTime(lastCheckedAt),
                databaseTime(lastConclusiveAt),
                databaseTime(nextCheckAt),
                databaseTime(synchronizedAt),
                command.resourceReference().value());

        if (existing.health() != derivation.health()) {
            insertHealthChangeEvent(
                    command.resourceReference().value(),
                    command.sourceRevision().value(),
                    null,
                    existing.health(),
                    derivation.health(),
                    synchronizedAt);
        }

        MonitorRow updated = new MonitorRow(
                command.resourceReference().value(),
                command.sourceRevision(),
                command.monitoringState(),
                requestedTarget,
                derivation.health(),
                derivation.consecutiveFailures(),
                lastOutcome,
                lastCheckedAt,
                lastConclusiveAt,
                nextCheckAt,
                null,
                null,
                null);
        return new SynchronizationResult(SynchronizationStatus.APPLIED, toProjection(updated));
    }

    private MonitorRow tryInsertMonitor(SynchronizeMonitorCommand command, Instant synchronizedAt) {
        String target = command.targetUrl().map(TargetUrl::value).orElse(null);
        Instant nextCheckAt = command.monitoringState() == MonitoringState.ACTIVE ? synchronizedAt : null;
        int inserted = jdbc.update("""
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
                """,
                command.resourceReference().value(),
                command.sourceRevision().value(),
                command.monitoringState().name(),
                target,
                databaseTime(nextCheckAt),
                databaseTime(synchronizedAt),
                databaseTime(synchronizedAt));
        if (inserted == 0) {
            return null;
        }
        return new MonitorRow(
                command.resourceReference().value(),
                command.sourceRevision(),
                command.monitoringState(),
                target,
                Health.UNKNOWN,
                0,
                null,
                null,
                null,
                nextCheckAt,
                null,
                null,
                null);
    }

    private List<ClaimedCheck> claimInTransaction(Instant claimedAt, Instant leaseUntil, int limit) {
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
                JdbcMonitoringPersistenceAdapter::mapMonitor,
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

    private CheckFinalizationResult finalizeInTransaction(CheckFinalization finalization) {
        if (resultExists(finalization.attemptId())) {
            return finalizationResult(CheckFinalizationStatus.ALREADY_FINALIZED);
        }

        List<AttemptRow> attempts = jdbc.query("""
                SELECT attempt_id, resource_reference, source_revision, target_url, lease_token, claimed_at
                FROM watch_attempt
                WHERE attempt_id = ?
                """, JdbcMonitoringPersistenceAdapter::mapAttempt, finalization.attemptId());
        if (attempts.isEmpty() || !attemptMatches(attempts.getFirst(), finalization)) {
            return finalizationResult(CheckFinalizationStatus.STALE_CLAIM);
        }
        AttemptRow attempt = attempts.getFirst();
        if (finalization.completedAt().isBefore(attempt.claimedAt())) {
            throw new IllegalArgumentException("completion cannot precede claim");
        }

        List<MonitorRow> monitors = jdbc.query(
                "SELECT " + MONITOR_COLUMNS
                        + " FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                JdbcMonitoringPersistenceAdapter::mapMonitor,
                finalization.resourceReference().value());

        if (resultExists(finalization.attemptId())) {
            return finalizationResult(CheckFinalizationStatus.ALREADY_FINALIZED);
        }
        if (monitors.isEmpty() || !monitorOwnsClaim(monitors.getFirst(), finalization)) {
            return finalizationResult(CheckFinalizationStatus.STALE_CLAIM);
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

        HealthDerivation derived = healthPolicy.derive(monitor.derivation(), observation.outcome());
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
            insertHealthChangeEvent(
                    finalization.resourceReference().value(),
                    finalization.sourceRevision().value(),
                    finalization.attemptId(),
                    monitor.health(),
                    derived.health(),
                    finalization.completedAt());
        }
        return finalizationResult(CheckFinalizationStatus.APPLIED);
    }

    private int markStaleInTransaction(Instant staleBefore, Instant markedAt, int limit) {
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
                JdbcMonitoringPersistenceAdapter::mapMonitor,
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
            insertHealthChangeEvent(
                    monitor.resourceReference(),
                    monitor.sourceRevision().value(),
                    null,
                    monitor.health(),
                    markedStale.health(),
                    markedAt);
        }
        return stale.size();
    }

    private void insertHealthChangeEvent(
            String resourceReference,
            long sourceRevision,
            UUID attemptId,
            Health previousHealth,
            Health currentHealth,
            Instant changedAt) {
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id,
                    resource_reference,
                    source_revision,
                    attempt_id,
                    previous_health,
                    current_health,
                    changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                resourceReference,
                sourceRevision,
                attemptId,
                previousHealth.name(),
                currentHealth.name(),
                databaseTime(changedAt));
    }

    private boolean resultExists(UUID attemptId) {
        return !jdbc.query("SELECT attempt_id FROM watch_result WHERE attempt_id = ?", (rs, row) -> 1, attemptId)
                .isEmpty();
    }

    private boolean attemptMatches(AttemptRow attempt, CheckFinalization finalization) {
        return attempt.attemptId().equals(finalization.attemptId())
                && attempt.leaseToken().equals(finalization.leaseToken())
                && attempt.resourceReference().equals(finalization.resourceReference().value())
                && attempt.sourceRevision().equals(finalization.sourceRevision());
    }

    private boolean monitorOwnsClaim(MonitorRow monitor, CheckFinalization finalization) {
        return monitor.monitoringState() == MonitoringState.ACTIVE
                && finalization.attemptId().equals(monitor.leaseAttemptId())
                && finalization.leaseToken().equals(monitor.leaseToken())
                && finalization.sourceRevision().equals(monitor.sourceRevision());
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

    private static MonitorRow mapMonitor(ResultSet resultSet, int ignoredRow) throws SQLException {
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
                resultSet.getObject("lease_attempt_id", UUID.class),
                instant(resultSet, "lease_expires_at"));
    }

    private static AttemptRow mapAttempt(ResultSet resultSet, int ignoredRow) throws SQLException {
        return new AttemptRow(
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getString("resource_reference"),
                new SourceRevision(resultSet.getLong("source_revision")),
                resultSet.getString("target_url"),
                resultSet.getObject("lease_token", UUID.class),
                instant(resultSet, "claimed_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static CheckFinalizationResult finalizationResult(CheckFinalizationStatus status) {
        return new CheckFinalizationResult(status);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static <T> T requireTransactionResult(T result) {
        return Objects.requireNonNull(result, "transaction callback result");
    }

    private record MonitorRow(
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
            UUID leaseAttemptId,
            Instant leaseExpiresAt) {

        private HealthDerivation derivation() {
            return new HealthDerivation(health, consecutiveFailures);
        }
    }

    private record AttemptRow(
            UUID attemptId,
            String resourceReference,
            SourceRevision sourceRevision,
            String targetUrl,
            UUID leaseToken,
            Instant claimedAt) {
    }
}
