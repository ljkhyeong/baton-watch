package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RunEventDeliveriesService implements RunEventDeliveriesUseCase {

    private final HealthChangeEventDeliveryPersistencePort persistence;
    private final HealthChangeEventSender sender;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int batchSize;

    public RunEventDeliveriesService(
            HealthChangeEventDeliveryPersistencePort persistence,
            HealthChangeEventSender sender,
            Clock clock,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            int batchSize) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        this.maxBackoff = requirePositive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff cannot be shorter than initialBackoff");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public synchronized EventDeliveryBatchResult runEventDeliveries() {
        Instant claimedAt = clock.instant();
        List<ClaimedHealthChangeEvent> claimedEvents = List.copyOf(Objects.requireNonNull(
                persistence.claimPendingEvents(claimedAt, claimedAt.plus(leaseDuration), batchSize),
                "claimed events"));
        if (claimedEvents.size() > batchSize) {
            throw new IllegalStateException("persistence returned more events than requested");
        }

        int delivered = 0;
        int retryScheduled = 0;
        int alreadyDelivered = 0;
        int staleClaims = 0;
        Map<EventDeliveryOutcome, Integer> outcomes = new EnumMap<>(EventDeliveryOutcome.class);
        for (ClaimedHealthChangeEvent event : claimedEvents) {
            Objects.requireNonNull(event, "claimed event");
            EventDeliveryObservation observation = send(event);
            outcomes.merge(observation.outcome(), 1, Integer::sum);
            Instant completedAt = clock.instant();
            Instant nextAttemptAt = observation.outcome().isDelivered()
                    ? null
                    : completedAt.plus(retryDelay(event.deliveryAttempt()));
            EventDeliveryFinalizationResult result = Objects.requireNonNull(
                    persistence.finalizeDelivery(new EventDeliveryFinalization(
                            event.eventId(),
                            event.leaseToken(),
                            event.deliveryAttempt(),
                            observation,
                            completedAt,
                            nextAttemptAt)),
                    "delivery finalization result");
            switch (result.status()) {
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
                claimedEvents.size(),
                delivered,
                retryScheduled,
                alreadyDelivered,
                staleClaims,
                outcomes);
    }

    private EventDeliveryObservation send(ClaimedHealthChangeEvent event) {
        try {
            return Objects.requireNonNull(sender.send(event), "delivery observation");
        } catch (RuntimeException ignored) {
            return EventDeliveryObservation.internalFailure();
        }
    }

    private Duration retryDelay(int deliveryAttempt) {
        Duration delay = initialBackoff;
        int remainingDoublings = deliveryAttempt - 1;
        while (remainingDoublings > 0 && delay.compareTo(maxBackoff) < 0) {
            try {
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
            } catch (ArithmeticException ignored) {
                return maxBackoff;
            }
            remainingDoublings--;
        }
        return delay;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
