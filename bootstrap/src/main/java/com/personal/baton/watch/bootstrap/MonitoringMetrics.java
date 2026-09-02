package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private static final String CHECK_LEASE_RECOVERIES = "baton.watch.check.lease.recoveries";
    private static final String DELIVERY_CLAIMED = "baton.watch.event.delivery.claimed";
    private static final String DELIVERY_ATTEMPTS = "baton.watch.event.delivery.attempts";
    private static final String DELIVERY_DURATION = "baton.watch.event.delivery.duration";
    private static final String DELIVERY_FINALIZATIONS = "baton.watch.event.delivery.finalizations";
    private static final String DELIVERY_LEASE_RECOVERIES =
            "baton.watch.event.delivery.lease.recoveries";
    private static final String MAINTENANCE_ITEMS = "baton.watch.maintenance.items";

    private final MeterRegistry registry;
    private final AtomicLong inFlightChecks = new AtomicLong();
    private final AtomicLong inFlightDeliveries = new AtomicLong();
    private final AtomicLong maximumCheckScheduleDelaySeconds = new AtomicLong();
    private final AtomicLong eventDeliveryBacklog = new AtomicLong();
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong();
    private final AtomicLong databaseClockOffsetMillis = new AtomicLong();

    MonitoringMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(
                        "baton.watch.check.inflight",
                        inFlightChecks,
                        AtomicLong::get)
                .description("현재 실행 중인 URL 점검 수")
                .register(registry);
        Gauge.builder(
                        "baton.watch.check.schedule.delay",
                        maximumCheckScheduleDelaySeconds,
                        AtomicLong::get)
                .baseUnit("seconds")
                .description("현재 가장 오래된 선점 가능 점검의 일정 지연")
                .register(registry);
        Gauge.builder(
                        "baton.watch.event.delivery.inflight",
                        inFlightDeliveries,
                        AtomicLong::get)
                .description("현재 실행 중인 상태 변경 이벤트 전달 수")
                .register(registry);
        Gauge.builder("baton.watch.event.delivery.backlog", eventDeliveryBacklog, AtomicLong::get)
                .description("아직 전달되지 않은 상태 변경 이벤트 수")
                .register(registry);
        Gauge.builder("baton.watch.event.delivery.oldest.age", oldestEventAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("가장 오래된 미전달 상태 변경 이벤트의 경과 시간")
                .register(registry);
        Gauge.builder(
                        "baton.watch.database.clock.offset",
                        databaseClockOffsetMillis,
                        value -> value.get() / 1_000.0)
                .baseUnit("seconds")
                .description("JVM 시각에서 PostgreSQL 시각을 뺀 값")
                .register(registry);
    }

    void checkStarted() {
        inFlightChecks.incrementAndGet();
    }

    void checkFinished() {
        inFlightChecks.decrementAndGet();
    }

    void updateCheckScheduleDelay(Duration delay) {
        maximumCheckScheduleDelaySeconds.set(delay.toSeconds());
    }

    void recordCheckClaim(ClaimedCheck claimed) {
        increment(CHECK_CLAIMED, 1);
        if (claimed.recoveredLease()) {
            increment(CHECK_LEASE_RECOVERIES, 1);
        }
    }

    void recordCheckFinalization(CheckFinalizationStatus status) {
        increment(CHECK_FINALIZATIONS, "status", status.name().toLowerCase(Locale.ROOT), 1);
    }

    void recordCheckFinalizationFailure() {
        increment(CHECK_FINALIZATIONS, "status", "failure", 1);
    }

    void recordCheckAttempt(CheckObservation observation) {
        String outcome = observation.outcome().name().toLowerCase(Locale.ROOT);
        increment(CHECK_ATTEMPTS, "outcome", outcome, 1);
        registry.timer(CHECK_DURATION, "outcome", outcome).record(observation.duration());
    }

    void recordEventDeliveryClaim(ClaimedHealthChangeEvent claimed) {
        increment(DELIVERY_CLAIMED, 1);
        if (claimed.recoveredLease()) {
            increment(DELIVERY_LEASE_RECOVERIES, 1);
        }
    }

    void recordEventDeliveryFinalization(
            EventDeliveryFinalization finalization,
            EventDeliveryFinalizationStatus status) {
        String metricStatus = switch (status) {
            case APPLIED -> finalization.observation().outcome().isDelivered()
                    ? "delivered"
                    : "retry_scheduled";
            case ALREADY_DELIVERED -> "already_delivered";
            case STALE_CLAIM -> "stale_claim";
        };
        increment(DELIVERY_FINALIZATIONS, "status", metricStatus, 1);
    }

    void recordEventDeliveryFinalizationFailure() {
        increment(DELIVERY_FINALIZATIONS, "status", "failure", 1);
    }

    void recordEventDeliveryAttempt(EventDeliveryOutcome outcome) {
        increment(DELIVERY_ATTEMPTS, "outcome", outcome.name().toLowerCase(Locale.ROOT), 1);
    }

    Timer.Sample eventDeliveryStarted() {
        inFlightDeliveries.incrementAndGet();
        try {
            return Timer.start(registry);
        } catch (RuntimeException exception) {
            inFlightDeliveries.decrementAndGet();
            throw exception;
        }
    }

    void eventDeliveryFinished(Timer.Sample sample, EventDeliveryOutcome outcome) {
        try {
            sample.stop(registry.timer(
                    DELIVERY_DURATION,
                    "outcome",
                    outcome.name().toLowerCase(Locale.ROOT)));
        } finally {
            inFlightDeliveries.decrementAndGet();
        }
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

    void updateDatabaseClockOffset(Duration offset) {
        databaseClockOffsetMillis.set(offset.toMillis());
    }

    private void increment(String name, double amount) {
        if (amount > 0) {
            BestEffortMetrics.record(() -> registry.counter(name).increment(amount));
        }
    }

    private void increment(String name, String tagName, String tagValue, double amount) {
        if (amount > 0) {
            BestEffortMetrics.record(() ->
                    registry.counter(name, tagName, tagValue).increment(amount));
        }
    }

}
