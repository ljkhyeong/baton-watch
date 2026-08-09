package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
final class MonitoringMetrics {

    private static final String CHECK_CLAIMED = "baton.watch.check.claimed";
    private static final String CHECK_FINALIZATIONS = "baton.watch.check.finalizations";
    private static final String DELIVERY_CLAIMED = "baton.watch.event.delivery.claimed";
    private static final String DELIVERY_ATTEMPTS = "baton.watch.event.delivery.attempts";
    private static final String DELIVERY_FINALIZATIONS = "baton.watch.event.delivery.finalizations";
    private static final String MAINTENANCE_ITEMS = "baton.watch.maintenance.items";

    private final MeterRegistry registry;
    private final AtomicLong eventDeliveryBacklog = new AtomicLong();
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong();

    MonitoringMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Gauge.builder("baton.watch.event.delivery.backlog", eventDeliveryBacklog, AtomicLong::get)
                .description("Health-change events that are not yet delivered")
                .register(registry);
        Gauge.builder("baton.watch.event.delivery.oldest.age", oldestEventAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("Age of the oldest undelivered health-change event")
                .register(registry);
    }

    void recordCheckBatch(DueCheckBatchResult result) {
        Objects.requireNonNull(result, "result");
        increment(CHECK_CLAIMED, result.claimed());
        increment(CHECK_FINALIZATIONS, "status", "applied", result.applied());
        increment(CHECK_FINALIZATIONS, "status", "already_finalized", result.alreadyFinalized());
        increment(CHECK_FINALIZATIONS, "status", "stale_claim", result.staleClaims());
    }

    void recordEventDeliveryBatch(EventDeliveryBatchResult result) {
        Objects.requireNonNull(result, "result");
        increment(DELIVERY_CLAIMED, result.claimed());
        increment(DELIVERY_FINALIZATIONS, "status", "delivered", result.delivered());
        increment(DELIVERY_FINALIZATIONS, "status", "retry_scheduled", result.retryScheduled());
        increment(DELIVERY_FINALIZATIONS, "status", "already_delivered", result.alreadyDelivered());
        increment(DELIVERY_FINALIZATIONS, "status", "stale_claim", result.staleClaims());
    }

    void recordEventDeliveryAttempt(EventDeliveryOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        increment(DELIVERY_ATTEMPTS, "outcome", outcome.name().toLowerCase(Locale.ROOT), 1);
    }

    void recordMonitoringMaintenance(int staleProjections, int purgedAttempts) {
        increment(MAINTENANCE_ITEMS, "operation", "stale_projection", staleProjections);
        increment(MAINTENANCE_ITEMS, "operation", "attempt_purged", purgedAttempts);
    }

    void recordPurgedDeliveredEvents(int purgedEvents) {
        increment(MAINTENANCE_ITEMS, "operation", "delivered_event_purged", purgedEvents);
    }

    void updateEventDeliveryBacklog(long pendingCount, Optional<Duration> oldestAge) {
        if (pendingCount < 0) {
            throw new IllegalArgumentException("pendingCount must be non-negative");
        }
        eventDeliveryBacklog.set(pendingCount);
        oldestEventAgeSeconds.set(oldestAge
                .map(Duration::toSeconds)
                .map(age -> Math.max(0L, age))
                .orElse(0L));
    }

    private void increment(String name, double amount) {
        if (amount > 0) {
            registry.counter(name).increment(amount);
        }
    }

    private void increment(String name, String tagName, String tagValue, double amount) {
        if (amount > 0) {
            registry.counter(name, tagName, tagValue).increment(amount);
        }
    }

}
