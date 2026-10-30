package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.instant;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** 상태 변경 이벤트의 전달 예약·점유·결과를 DB에 저장한다. */
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

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;

    public JdbcHealthChangeEventDeliveryAdapter(
            JdbcClient jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Optional<ClaimedHealthChangeEvent> claimPendingEvent(Duration leaseDuration) {
        return transactions.execute(ignored -> claimInTransaction(leaseDuration));
    }

    @Override
    public EventDeliveryFinalizationStatus finalizeDelivery(EventDeliveryFinalization finalization) {
        return transactions.execute(ignored -> finalizeInTransaction(finalization));
    }

    @Override
    public int purgeDeliveredEvents(Instant deliveredBefore, int limit) {
        return transactions.execute(ignored -> jdbc.sql("""
                        WITH candidates AS MATERIALIZED (
                            SELECT event_id
                            FROM watch_health_change_event
                            WHERE delivery_status = 'DELIVERED'
                              AND delivered_at < ?
                            ORDER BY delivered_at, event_id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM watch_health_change_event event
                        USING candidates
                        WHERE event.event_id = candidates.event_id
                        """)
                .params(databaseTime(deliveredBefore), limit)
                .update());
    }

    @Override
    public EventDeliveryBacklogSnapshot getBacklogSnapshot() {
        return jdbc.sql("""
                        SELECT count(*) AS pending_count, min(changed_at) AS oldest_changed_at
                        FROM watch_health_change_event
                        WHERE delivery_status = 'PENDING'
                        """)
                .query((resultSet, ignoredRow) -> new EventDeliveryBacklogSnapshot(
                        resultSet.getLong("pending_count"),
                        Optional.ofNullable(instant(resultSet, "oldest_changed_at"))))
                .single();
    }

    private Optional<ClaimedHealthChangeEvent> claimInTransaction(Duration leaseDuration) {
        ClaimableEvent pending = jdbc.sql(
                        "SELECT " + DELIVERY_COLUMNS + """
                                , transaction_timestamp() AS claimed_at
                                 FROM watch_health_change_event
                                 WHERE delivery_status = 'PENDING'
                                   AND next_attempt_at <= transaction_timestamp()
                                   AND (delivery_lease_expires_at IS NULL
                                        OR delivery_lease_expires_at <= transaction_timestamp())
                                 ORDER BY next_attempt_at, changed_at, event_id
                                 LIMIT 1
                                 FOR UPDATE SKIP LOCKED
                                """)
                .query((resultSet, row) -> new ClaimableEvent(
                        mapDelivery(resultSet, row), instant(resultSet, "claimed_at")))
                .optional()
                .orElse(null);
        if (pending == null) {
            return Optional.empty();
        }
        DeliveryRow event = pending.event();
        Instant claimedAt = pending.claimedAt();
        Instant leaseUntil = claimedAt.plus(leaseDuration);

        UUID leaseToken = UUID.randomUUID();
        int deliveryAttempt = Math.clamp(
                (long) event.deliveryAttempt() + 1, 1, Integer.MAX_VALUE);
        jdbc.sql("""
                        UPDATE watch_health_change_event
                        SET delivery_attempt = ?,
                            delivery_lease_token = ?,
                            delivery_lease_expires_at = ?
                        WHERE event_id = ?
                        """)
                .params(deliveryAttempt, leaseToken, databaseTime(leaseUntil), event.eventId())
                .update();
        return Optional.of(new ClaimedHealthChangeEvent(
                new HealthChangeEventPayload(
                        event.eventId(),
                        new ResourceReference(event.resourceReference()),
                        new SourceRevision(event.sourceRevision()),
                        Optional.ofNullable(event.attemptId()),
                        event.previousHealth(),
                        event.currentHealth(),
                        event.changedAt()),
                leaseToken,
                deliveryAttempt,
                claimedAt,
                event.leaseToken() != null));
    }

    private EventDeliveryFinalizationStatus finalizeInTransaction(EventDeliveryFinalization finalization) {
        Optional<DeliveryRow> locked = jdbc.sql(
                        "SELECT " + DELIVERY_COLUMNS
                                + " FROM watch_health_change_event WHERE event_id = ? FOR UPDATE")
                .param(finalization.eventId())
                .query(JdbcHealthChangeEventDeliveryAdapter::mapDelivery)
                .optional();
        if (locked.isEmpty()) {
            return EventDeliveryFinalizationStatus.STALE_CLAIM;
        }

        DeliveryRow event = locked.get();
        if (event.deliveryStatus() == DeliveryStatus.DELIVERED) {
            return EventDeliveryFinalizationStatus.ALREADY_DELIVERED;
        }
        if (!finalization.leaseToken().equals(event.leaseToken())) {
            return EventDeliveryFinalizationStatus.STALE_CLAIM;
        }
        if (finalization.completedAt().isBefore(event.changedAt())) {
            throw new IllegalArgumentException("delivery completion cannot precede the event");
        }

        // 성공이면 다음 시도 시각이 없고, 실패면 전달 시각이 없다는 조합은 레코드와 DB CHECK가 함께 보장한다.
        boolean delivered = finalization.observation().outcome().isDelivered();
        jdbc.sql("""
                        UPDATE watch_health_change_event
                        SET delivery_status = ?,
                            next_attempt_at = ?,
                            delivered_at = ?,
                            delivery_lease_token = NULL,
                            delivery_lease_expires_at = NULL,
                            last_delivery_outcome = ?,
                            last_http_status_code = ?
                        WHERE event_id = ?
                        """)
                .params(
                        (delivered ? DeliveryStatus.DELIVERED : DeliveryStatus.PENDING).name(),
                        databaseTime(finalization.nextAttemptAt()),
                        delivered ? databaseTime(finalization.completedAt()) : null,
                        finalization.observation().outcome().name(),
                        finalization.observation().httpStatusCode(),
                        finalization.eventId())
                .update();
        return EventDeliveryFinalizationStatus.APPLIED;
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

    private record ClaimableEvent(DeliveryRow event, Instant claimedAt) {
    }
}
