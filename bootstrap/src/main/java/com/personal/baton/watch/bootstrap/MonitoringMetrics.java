package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
final class MonitoringMetrics {

    private static final String CHECK_CLAIMED = "baton.watch.check.claimed";
    private static final String CHECK_ATTEMPTS = "baton.watch.check.attempts";
    private static final String CHECK_DURATION = "baton.watch.check.duration";
    private static final String CHECK_FINALIZATIONS = "baton.watch.check.finalizations";
    private static final String DELIVERY_CLAIMED = "baton.watch.event.delivery.claimed";
    private static final String DELIVERY_ATTEMPTS = "baton.watch.event.delivery.attempts";
    private static final String DELIVERY_FINALIZATIONS = "baton.watch.event.delivery.finalizations";
    private static final String MAINTENANCE_ITEMS = "baton.watch.maintenance.items";

    private final MeterRegistry registry;
    private final AtomicLong maximumCheckScheduleDelaySeconds = new AtomicLong();
    private final AtomicLong eventDeliveryBacklog = new AtomicLong();
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong();

    MonitoringMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(
                        "baton.watch.check.schedule.delay",
                        maximumCheckScheduleDelaySeconds,
                        AtomicLong::get)
                .baseUnit("seconds")
                .description("최근 점검 배치에서 선점한 작업의 최대 일정 지연")
                .register(registry);
        Gauge.builder("baton.watch.event.delivery.backlog", eventDeliveryBacklog, AtomicLong::get)
                .description("Health-change events that are not yet delivered")
                .register(registry);
        Gauge.builder("baton.watch.event.delivery.oldest.age", oldestEventAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("Age of the oldest undelivered health-change event")
                .register(registry);
    }

    void recordCheckBatch(DueCheckBatchResult result) {
        maximumCheckScheduleDelaySeconds.set(result.maximumScheduleDelay().toSeconds());
        increment(CHECK_CLAIMED, result.claimed());
        increment(CHECK_FINALIZATIONS, "status", "applied", result.applied());
        increment(CHECK_FINALIZATIONS, "status", "already_finalized", result.alreadyFinalized());
        increment(CHECK_FINALIZATIONS, "status", "stale_claim", result.staleClaims());
    }

    void recordCheckAttempt(CheckObservation observation) {
        String outcome = observation.outcome().name().toLowerCase(Locale.ROOT);
        increment(CHECK_ATTEMPTS, "outcome", outcome, 1);
        registry.timer(CHECK_DURATION, "outcome", outcome).record(observation.duration());
    }

    void recordEventDeliveryBatch(EventDeliveryBatchResult result) {
        increment(DELIVERY_CLAIMED, result.claimed());
        increment(DELIVERY_FINALIZATIONS, "status", "delivered", result.delivered());
        increment(DELIVERY_FINALIZATIONS, "status", "retry_scheduled", result.retryScheduled());
        increment(DELIVERY_FINALIZATIONS, "status", "already_delivered", result.alreadyDelivered());
        increment(DELIVERY_FINALIZATIONS, "status", "stale_claim", result.staleClaims());
    }

    void recordEventDeliveryAttempt(EventDeliveryOutcome outcome) {
        increment(DELIVERY_ATTEMPTS, "outcome", outcome.name().toLowerCase(Locale.ROOT), 1);
    }

    void recordStaleProjections(int staleProjections) {
        increment(MAINTENANCE_ITEMS, "operation", "stale_projection", staleProjections);
    }

    void recordPurgedAttempts(int purgedAttempts) {
        increment(MAINTENANCE_ITEMS, "operation", "attempt_purged", purgedAttempts);
    }

    void recordPurgedDeliveredEvents(int purgedEvents) {
        increment(MAINTENANCE_ITEMS, "operation", "delivered_event_purged", purgedEvents);
    }

    void updateEventDeliveryBacklog(EventDeliveryBacklog backlog) {
        eventDeliveryBacklog.set(backlog.pendingCount());
        oldestEventAgeSeconds.set(backlog.oldestEventAge()
                .map(Duration::toSeconds)
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
