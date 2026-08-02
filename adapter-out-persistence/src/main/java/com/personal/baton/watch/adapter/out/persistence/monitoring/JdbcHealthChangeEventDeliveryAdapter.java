package com.personal.baton.watch.adapter.out.persistence.monitoring;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
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

public final class JdbcHealthChangeEventDeliveryAdapter implements HealthChangeEventDeliveryPersistencePort {

    private static final String DELIVERY_COLUMNS = """
            event_id,
            resource_reference,
            source_revision,
            attempt_id,
            previous_health,
            current_health,
            changed_at,
            delivery_status,
            delivery_attempt,
            delivery_lease_token
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcHealthChangeEventDeliveryAdapter(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public List<ClaimedHealthChangeEvent> claimPendingEvents(Instant claimedAt, Instant leaseUntil, int limit) {
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
    public EventDeliveryFinalizationResult finalizeDelivery(EventDeliveryFinalization finalization) {
        Objects.requireNonNull(finalization, "finalization");
        return requireTransactionResult(transactions.execute(ignored -> finalizeInTransaction(finalization)));
    }

    @Override
    public int purgeDeliveredEvents(Instant deliveredBefore, int limit) {
        Objects.requireNonNull(deliveredBefore, "deliveredBefore");
        requirePositiveLimit(limit);
        return requireTransactionResult(transactions.execute(ignored -> jdbc.update("""
                DELETE FROM watch_health_change_event
                WHERE event_id IN (
                    SELECT event_id
                    FROM watch_health_change_event
                    WHERE delivery_status = 'DELIVERED'
                      AND delivered_at < ?
                    ORDER BY delivered_at, event_id
                    LIMIT ?
                )
                """, databaseTime(deliveredBefore), limit)));
    }

    @Override
    public EventDeliveryBacklogSnapshot getBacklogSnapshot() {
        return jdbc.query("""
                SELECT COUNT(*) AS pending_count, MIN(changed_at) AS oldest_changed_at
                FROM watch_health_change_event
                WHERE delivery_status = 'PENDING'
                """, resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("delivery backlog aggregate returned no row");
                    }
                    long count = resultSet.getLong("pending_count");
                    return new EventDeliveryBacklogSnapshot(
                            count, Optional.ofNullable(instant(resultSet, "oldest_changed_at")));
                });
    }

    private List<ClaimedHealthChangeEvent> claimInTransaction(
            Instant claimedAt, Instant leaseUntil, int limit) {
        List<DeliveryRow> pending = jdbc.query(
                "SELECT " + DELIVERY_COLUMNS + """
                         FROM watch_health_change_event
                         WHERE delivery_status = 'PENDING'
                           AND next_attempt_at <= ?
                           AND (delivery_lease_expires_at IS NULL OR delivery_lease_expires_at <= ?)
                         ORDER BY next_attempt_at, changed_at, event_id
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                        """,
                JdbcHealthChangeEventDeliveryAdapter::mapDelivery,
                databaseTime(claimedAt),
                databaseTime(claimedAt),
                limit);

        return pending.stream().map(event -> {
            UUID leaseToken = UUID.randomUUID();
            int deliveryAttempt = event.deliveryAttempt() == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : event.deliveryAttempt() + 1;
            jdbc.update("""
                    UPDATE watch_health_change_event
                    SET delivery_attempt = ?,
                        delivery_lease_token = ?,
                        delivery_lease_expires_at = ?
                    WHERE event_id = ?
                    """,
                    deliveryAttempt,
                    leaseToken,
                    databaseTime(leaseUntil),
                    event.eventId());
            return new ClaimedHealthChangeEvent(
                    new HealthChangeEventPayload(
                            event.eventId(),
                            new ResourceReference(event.resourceReference()),
                            new SourceRevision(event.sourceRevision()),
                            Optional.ofNullable(event.attemptId()),
                            event.previousHealth(),
                            event.currentHealth(),
                            event.changedAt()),
                    leaseToken,
                    deliveryAttempt);
        }).toList();
    }

    private EventDeliveryFinalizationResult finalizeInTransaction(EventDeliveryFinalization finalization) {
        List<DeliveryRow> rows = jdbc.query(
                "SELECT " + DELIVERY_COLUMNS
                        + " FROM watch_health_change_event WHERE event_id = ? FOR UPDATE",
                JdbcHealthChangeEventDeliveryAdapter::mapDelivery,
                finalization.eventId());
        if (rows.isEmpty()) {
            return result(EventDeliveryFinalizationStatus.STALE_CLAIM);
        }

        DeliveryRow event = rows.getFirst();
        if (event.deliveryStatus() == DeliveryStatus.DELIVERED) {
            return result(EventDeliveryFinalizationStatus.ALREADY_DELIVERED);
        }
        if (!finalization.leaseToken().equals(event.leaseToken())
                || finalization.deliveryAttempt() != event.deliveryAttempt()) {
            return result(EventDeliveryFinalizationStatus.STALE_CLAIM);
        }
        if (finalization.completedAt().isBefore(event.changedAt())) {
            throw new IllegalArgumentException("delivery completion cannot precede the event");
        }

        if (finalization.observation().outcome().isDelivered()) {
            jdbc.update("""
                    UPDATE watch_health_change_event
                    SET delivery_status = 'DELIVERED',
                        next_attempt_at = NULL,
                        delivery_lease_token = NULL,
                        delivery_lease_expires_at = NULL,
                        delivered_at = ?,
                        last_delivery_outcome = ?,
                        last_http_status_code = ?
                    WHERE event_id = ?
                    """,
                    databaseTime(finalization.completedAt()),
                    finalization.observation().outcome().name(),
                    finalization.observation().httpStatusCode(),
                    finalization.eventId());
        } else {
            jdbc.update("""
                    UPDATE watch_health_change_event
                    SET next_attempt_at = ?,
                        delivery_lease_token = NULL,
                        delivery_lease_expires_at = NULL,
                        last_delivery_outcome = ?,
                        last_http_status_code = ?
                    WHERE event_id = ?
                    """,
                    databaseTime(finalization.nextAttemptAt()),
                    finalization.observation().outcome().name(),
                    finalization.observation().httpStatusCode(),
                    finalization.eventId());
        }
        return result(EventDeliveryFinalizationStatus.APPLIED);
    }

    private static DeliveryRow mapDelivery(ResultSet resultSet, int ignoredRow) throws SQLException {
        return new DeliveryRow(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("resource_reference"),
                resultSet.getLong("source_revision"),
                resultSet.getObject("attempt_id", UUID.class),
                Health.valueOf(resultSet.getString("previous_health")),
                Health.valueOf(resultSet.getString("current_health")),
                instant(resultSet, "changed_at"),
                DeliveryStatus.valueOf(resultSet.getString("delivery_status")),
                resultSet.getInt("delivery_attempt"),
                resultSet.getObject("delivery_lease_token", UUID.class));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static EventDeliveryFinalizationResult result(EventDeliveryFinalizationStatus status) {
        return new EventDeliveryFinalizationResult(status);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static <T> T requireTransactionResult(T result) {
        return Objects.requireNonNull(result, "transaction callback result");
    }

    private enum DeliveryStatus {
        PENDING,
        DELIVERED
    }

    private record DeliveryRow(
            UUID eventId,
            String resourceReference,
            long sourceRevision,
            UUID attemptId,
            Health previousHealth,
            Health currentHealth,
            Instant changedAt,
            DeliveryStatus deliveryStatus,
            int deliveryAttempt,
            UUID leaseToken) {
    }
}
