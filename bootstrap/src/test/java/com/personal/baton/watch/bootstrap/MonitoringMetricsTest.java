package com.personal.baton.watch.bootstrap;

import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.claimedCheck;
import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.claimedEvent;
import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.count;
import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.timer;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MonitoringMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Map<String, Set<String>> ALLOWED_TAG_KEYS = Map.ofEntries(
            Map.entry("baton.watch.check.inflight", Set.of()),
            Map.entry("baton.watch.check.schedule.delay", Set.of()),
            Map.entry("baton.watch.check.claimed", Set.of()),
            Map.entry("baton.watch.check.lease.recoveries", Set.of()),
            Map.entry("baton.watch.check.attempts", Set.of("outcome")),
            Map.entry("baton.watch.check.duration", Set.of("outcome")),
            Map.entry("baton.watch.check.finalizations", Set.of("status")),
            Map.entry("baton.watch.event.delivery.inflight", Set.of()),
            Map.entry("baton.watch.event.delivery.backlog", Set.of()),
            Map.entry("baton.watch.event.delivery.oldest.age", Set.of()),
            Map.entry("baton.watch.event.delivery.claimed", Set.of()),
            Map.entry("baton.watch.event.delivery.lease.recoveries", Set.of()),
            Map.entry("baton.watch.event.delivery.attempts", Set.of("outcome")),
            Map.entry("baton.watch.event.delivery.duration", Set.of("outcome")),
            Map.entry("baton.watch.event.delivery.finalizations", Set.of("status")),
            Map.entry("baton.watch.maintenance.items", Set.of("operation")),
            Map.entry("baton.watch.database.clock.offset", Set.of()));

    @Test
    void exposesZeroFailureAndRecoveryCountersBeforeFirstIncident() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MonitoringMetrics(registry);

        assertAll(
                () -> assertEquals(0.0, count(registry, "baton.watch.check.finalizations", "status", "failure")),
                () -> assertEquals(0.0, count(registry, "baton.watch.event.delivery.finalizations", "status", "failure")),
                () -> assertEquals(0.0, count(registry, "baton.watch.check.lease.recoveries")),
                () -> assertEquals(0.0, count(registry, "baton.watch.event.delivery.lease.recoveries")));
    }

    @Test
    void emitsOnlyBoundedCheckAndDeliveryTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        assertEquals(0.0, registry.get("baton.watch.check.inflight").gauge().value());
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());

        metrics.updateCheckScheduleDelay(Duration.ofSeconds(17));
        ClaimedCheck claimedCheck = claimedCheck(true);
        metrics.recordCheckClaim(claimedCheck);
        metrics.recordCheckClaim(claimedCheck);
        metrics.recordCheckClaim(claimedCheck);
        metrics.recordCheckFinalization(CheckFinalizationStatus.STALE_CLAIM);
        metrics.recordCheckAttempt(CheckObservation.failure(
                CheckOutcome.CONNECT_TIMEOUT,
                Duration.ofMillis(125),
                0));
        ClaimedHealthChangeEvent claimedEvent = claimedEvent(true);
        metrics.recordEventDeliveryClaim(claimedEvent);
        metrics.recordEventDeliveryClaim(claimedEvent);
        metrics.recordEventDeliveryClaim(claimedEvent);
        metrics.recordEventDeliveryClaim(claimedEvent);
        metrics.recordEventDeliveryFinalization(
                new EventDeliveryFinalization(
                        claimedEvent.payload().eventId(),
                        claimedEvent.leaseToken(),
                        EventDeliveryObservation.failure(EventDeliveryOutcome.CONNECT_TIMEOUT),
                        NOW,
                        NOW.plusSeconds(1)),
                EventDeliveryFinalizationStatus.APPLIED);
        metrics.recordEventDeliveryAttempt(EventDeliveryOutcome.CONNECT_TIMEOUT);
        Timer.Sample deliverySample = metrics.eventDeliveryStarted();
        assertEquals(1.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        metrics.eventDeliveryFinished(deliverySample, EventDeliveryOutcome.CONNECT_TIMEOUT);

        assertEquals(3.0, count(registry, "baton.watch.check.claimed"));
        assertEquals(3.0, count(registry, "baton.watch.check.lease.recoveries"));
        assertEquals(4.0, count(registry, "baton.watch.event.delivery.claimed"));
        assertEquals(4.0, count(registry, "baton.watch.event.delivery.lease.recoveries"));
        assertEquals(17.0, registry.get("baton.watch.check.schedule.delay").gauge().value());
        assertEquals(1.0, count(registry, "baton.watch.check.attempts", "outcome", "connect_timeout"));
        assertEquals(125.0, timer(registry, "baton.watch.check.duration", "outcome", "connect_timeout")
                .totalTime(TimeUnit.MILLISECONDS));
        assertEquals(1.0, count(registry, "baton.watch.check.finalizations", "status", "stale_claim"));
        assertEquals(1.0, count(registry, "baton.watch.event.delivery.finalizations", "status", "retry_scheduled"));
        assertEquals(1.0, count(registry, "baton.watch.event.delivery.attempts", "outcome", "connect_timeout"));
        assertEquals(0.0, registry.get("baton.watch.event.delivery.inflight").gauge().value());
        assertEquals(1L, timer(registry, "baton.watch.event.delivery.duration", "outcome", "connect_timeout").count());

        metrics.updateEventDeliveryBacklog(new EventDeliveryBacklog(1, Optional.of(Duration.ofSeconds(1))));
        metrics.recordStaleProjections(1);
        metrics.recordPurgedAttempts(1);
        metrics.recordPurgedDeliveredEvents(1);
        metrics.updateDatabaseClockOffset(Duration.ofSeconds(1));
        assertOnlyAllowedTags(registry);

        metrics.updateCheckScheduleDelay(Duration.ZERO);
        assertEquals(0.0, registry.get("baton.watch.check.schedule.delay").gauge().value());
    }

    @Test
    void recordsMonitoringMaintenanceItemsIndependently() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.recordStaleProjections(2);
        metrics.recordPurgedAttempts(3);
        metrics.recordPurgedDeliveredEvents(4);

        assertEquals(2.0, count(registry, "baton.watch.maintenance.items", "operation", "stale_projection"));
        assertEquals(3.0, count(registry, "baton.watch.maintenance.items", "operation", "attempt_purged"));
        assertEquals(4.0, count(registry, "baton.watch.maintenance.items", "operation", "delivered_event_purged"));
    }

    @Test
    void exportsGaugesUnderTheNamesUsedByAlertRulesAndDashboards() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        metrics.updateCheckScheduleDelay(Duration.ofSeconds(17));
        metrics.updateEventDeliveryBacklog(new EventDeliveryBacklog(1, Optional.of(Duration.ofSeconds(91))));
        metrics.updateDatabaseClockOffset(Duration.ofMillis(-1_500));

        // 경보 규칙과 대시보드가 참조하는 Prometheus 이름과 값을 유지한다. 시간 게이지는 초 단위다.
        Map<String, Double> scraped = registry.scrape().lines()
                .filter(line -> !line.startsWith("#"))
                .collect(Collectors.toMap(
                        line -> line.substring(0, line.lastIndexOf(' ')),
                        line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))));
        assertAll(
                () -> assertEquals(0.0, scraped.get("baton_watch_check_inflight")),
                () -> assertEquals(0.0, scraped.get("baton_watch_event_delivery_inflight")),
                () -> assertEquals(1.0, scraped.get("baton_watch_event_delivery_backlog")),
                () -> assertEquals(17.0, scraped.get("baton_watch_check_schedule_delay_seconds")),
                () -> assertEquals(91.0, scraped.get("baton_watch_event_delivery_oldest_age_seconds")),
                () -> assertEquals(-1.5, scraped.get("baton_watch_database_clock_offset_seconds")));
    }

    @Test
    void ignoresCounterFailures() {
        // 게이지 등록은 실제 레지스트리로 두고 카운터 기록만 실패시킨다.
        MeterRegistry registry = spy(new SimpleMeterRegistry());
        doThrow(new IllegalStateException("meter registry unavailable"))
                .when(registry).counter(anyString(), any(String[].class));
        MonitoringMetrics metrics = new MonitoringMetrics(registry);

        assertDoesNotThrow(() -> metrics.recordCheckClaim(claimedCheck(false)));
        assertDoesNotThrow(() -> metrics.recordStaleProjections(1));
    }

    private static void assertOnlyAllowedTags(SimpleMeterRegistry registry) {
        Map<String, Set<String>> actualTagKeys = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("baton.watch."))
                .collect(Collectors.toMap(
                        meter -> meter.getId().getName(),
                        meter -> meter.getId().getTags().stream()
                                .map(tag -> tag.getKey())
                                .collect(Collectors.toUnmodifiableSet()),
                        (left, right) -> {
                            assertEquals(left, right);
                            return left;
                        }));

        assertEquals(ALLOWED_TAG_KEYS, actualTagKeys);
    }
}
