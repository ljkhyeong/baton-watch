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
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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

    private final MeterRegistry registry;
    private final AtomicLong inFlightChecks = new AtomicLong();
    private final AtomicLong inFlightDeliveries = new AtomicLong();
    private final AtomicLong maximumCheckScheduleDelaySeconds = new AtomicLong();
    private final AtomicLong eventDeliveryBacklog = new AtomicLong();
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong();
    private final AtomicLong databaseClockOffsetMillis = new AtomicLong();

    MonitoringMetrics(MeterRegistry registry) {
        this.registry = registry;
        gauge("baton.watch.check.inflight", "현재 실행 중인 URL 점검 수", inFlightChecks);
        timeGauge(
                "baton.watch.check.schedule.delay",
                "지금 실행 가능한 점검 중 최대 지연 시간",
                maximumCheckScheduleDelaySeconds,
                TimeUnit.SECONDS);
        gauge("baton.watch.event.delivery.inflight", "현재 실행 중인 상태 변경 이벤트 전달 수", inFlightDeliveries);
        gauge("baton.watch.event.delivery.backlog", "아직 전달되지 않은 상태 변경 이벤트 수", eventDeliveryBacklog);
        timeGauge(
                "baton.watch.event.delivery.oldest.age",
                "미전달 상태 변경 이벤트의 최대 대기 시간",
                oldestEventAgeSeconds,
                TimeUnit.SECONDS);
        timeGauge(
                "baton.watch.database.clock.offset",
                "JVM 시각에서 PostgreSQL 시각을 뺀 값",
                databaseClockOffsetMillis,
                TimeUnit.MILLISECONDS);
        // 첫 실패·리스 회수 전의 0도 수집하도록 경보용 카운터를 미리 등록한다.
        registry.counter(CHECK_FINALIZATIONS, "status", "failure");
        registry.counter(DELIVERY_FINALIZATIONS, "status", "failure");
        registry.counter(CHECK_LEASE_RECOVERIES);
        registry.counter(DELIVERY_LEASE_RECOVERIES);
    }

    void checkStarted() {
        inFlightChecks.incrementAndGet();
    }

    void checkFinished(CheckObservation observation) {
        inFlightChecks.decrementAndGet();
        String outcome = observation.outcome().name().toLowerCase(Locale.ROOT);
        registry.counter(CHECK_ATTEMPTS, "outcome", outcome).increment();
        registry.timer(CHECK_DURATION, "outcome", outcome).record(observation.duration());
    }

    void updateCheckScheduleDelay(Duration delay) {
        maximumCheckScheduleDelaySeconds.set(delay.toSeconds());
    }

    void recordCheckClaim(ClaimedCheck claimed) {
        registry.counter(CHECK_CLAIMED).increment();
        if (claimed.recoveredLease()) {
            registry.counter(CHECK_LEASE_RECOVERIES).increment();
        }
    }

    void recordCheckFinalization(CheckFinalizationStatus status) {
        registry.counter(CHECK_FINALIZATIONS, "status", status.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    void recordCheckFinalizationFailure() {
        registry.counter(CHECK_FINALIZATIONS, "status", "failure").increment();
    }

    void recordEventDeliveryClaim(ClaimedHealthChangeEvent claimed) {
        registry.counter(DELIVERY_CLAIMED).increment();
        if (claimed.recoveredLease()) {
            registry.counter(DELIVERY_LEASE_RECOVERIES).increment();
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
        registry.counter(DELIVERY_FINALIZATIONS, "status", metricStatus).increment();
    }

    void recordEventDeliveryFinalizationFailure() {
        registry.counter(DELIVERY_FINALIZATIONS, "status", "failure").increment();
    }

    Timer.Sample eventDeliveryStarted() {
        inFlightDeliveries.incrementAndGet();
        return Timer.start(registry);
    }

    void eventDeliveryFinished(Timer.Sample sample, EventDeliveryOutcome outcome) {
        inFlightDeliveries.decrementAndGet();
        String metricOutcome = outcome.name().toLowerCase(Locale.ROOT);
        registry.counter(DELIVERY_ATTEMPTS, "outcome", metricOutcome).increment();
        sample.stop(registry.timer(DELIVERY_DURATION, "outcome", metricOutcome));
    }

    void updateEventDeliveryBacklog(EventDeliveryBacklog backlog) {
        eventDeliveryBacklog.set(backlog.pendingCount());
        oldestEventAgeSeconds.set(backlog.oldestEventAge().toSeconds());
    }

    void updateDatabaseClockOffset(Duration offset) {
        databaseClockOffsetMillis.set(offset.toMillis());
    }

    private void gauge(String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }

    private void timeGauge(String name, String description, AtomicLong value, TimeUnit unit) {
        TimeGauge.builder(name, value, unit, AtomicLong::get).description(description).register(registry);
    }
}
