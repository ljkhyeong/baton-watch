package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class RunEventDeliveriesService implements RunEventDeliveriesUseCase {

    private final HealthChangeEventDeliveryPersistencePort persistence;
    private final HealthChangeEventSender sender;
    private final Clock clock;
    private final Duration leaseDuration;
    private final EventDeliveryRetryPolicy retryPolicy;
    private final int batchSize;

    public RunEventDeliveriesService(
            HealthChangeEventDeliveryPersistencePort persistence,
            HealthChangeEventSender sender,
            Clock clock,
            Duration leaseDuration,
            EventDeliveryRetryPolicy retryPolicy,
            int batchSize) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = TimeBoundaryPolicy.requireSupportedOffset(leaseDuration, "leaseDuration");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public EventDeliveryBatchResult runEventDeliveries() {
        int claimed = 0;
        int delivered = 0;
        int retryScheduled = 0;
        int alreadyDelivered = 0;
        int staleClaims = 0;
        while (claimed < batchSize && !Thread.currentThread().isInterrupted()) {
            Optional<ClaimedHealthChangeEvent> nextEvent =
                    persistence.claimPendingEvent(leaseDuration);
            if (nextEvent.isEmpty()) {
                break;
            }
            ClaimedHealthChangeEvent event = nextEvent.orElseThrow();
            claimed++;
            EventDeliveryObservation observation = send(event);
            Instant observedAt = clock.instant();
            Instant completedAt = observedAt.isBefore(event.claimedAt())
                    ? event.claimedAt()
                    : observedAt;
            Instant nextAttemptAt = observation.outcome().isDelivered()
                    ? null
                    : retryPolicy.nextAttemptAt(completedAt, event.deliveryAttempt());
            EventDeliveryFinalizationStatus status = persistence.finalizeDelivery(
                    new EventDeliveryFinalization(
                            event.payload().eventId(),
                            event.leaseToken(),
                            observation,
                            completedAt,
                            nextAttemptAt));
            switch (status) {
                case APPLIED -> {
                    if (observation.outcome().isDelivered()) {
                        delivered++;
                    } else {
                        retryScheduled++;
                    }
                }
                case ALREADY_DELIVERED -> alreadyDelivered++;
                case STALE_CLAIM -> staleClaims++;
            }
        }
        return new EventDeliveryBatchResult(
                claimed,
                delivered,
                retryScheduled,
                alreadyDelivered,
                staleClaims);
    }

    private EventDeliveryObservation send(ClaimedHealthChangeEvent event) {
        try {
            return Objects.requireNonNull(sender.send(event.payload()), "delivery observation");
        } catch (RuntimeException ignored) {
            return EventDeliveryObservation.internalFailure();
        }
    }

}
