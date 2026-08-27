package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MeteredUrlCheckerTest {

    private static final TargetUrl TARGET = new TargetUrl("https://sensitive.example/check?token=secret");

    @Test
    void recordsBoundedOutcomeAndDurationWithoutTargetData() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MonitoringMetrics metrics = new MonitoringMetrics(registry);
        CheckObservation expected = CheckObservation.forHttpStatus(
                204,
                Duration.ofMillis(37),
                123,
                0);
        MeteredUrlChecker checker = new MeteredUrlChecker(
                ignored -> {
                    assertEquals(1.0, registry.get("baton.watch.check.inflight").gauge().value());
                    return expected;
                },
                metrics);

        CheckObservation actual = checker.check(TARGET);

        assertSame(expected, actual);
        assertEquals(0.0, registry.get("baton.watch.check.inflight").gauge().value());
        assertEquals(
                1.0,
                registry.get("baton.watch.check.attempts")
                        .tag("outcome", "success")
                        .counter()
                        .count());
        assertEquals(
                37.0,
                registry.get("baton.watch.check.duration")
                        .tag("outcome", "success")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS));
        assertTrue(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("sensitive.example")
                        || tag.getValue().contains("secret")));
    }

    @Test
    void convertsUnexpectedCheckerErrorsAndRecordsTheInternalOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredUrlChecker checker = new MeteredUrlChecker(
                ignored -> {
                    throw new IllegalStateException("sensitive target detail");
                },
                new MonitoringMetrics(registry));

        CheckObservation observation = checker.check(TARGET);

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(0.0, registry.get("baton.watch.check.inflight").gauge().value());
        assertEquals(
                1.0,
                registry.get("baton.watch.check.attempts")
                        .tag("outcome", "internal_failure")
                        .counter()
                        .count());
    }

    @Test
    void convertsNullCheckerResultsToInternalFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredUrlChecker checker = new MeteredUrlChecker(
                ignored -> null,
                new MonitoringMetrics(registry));

        CheckObservation observation = checker.check(TARGET);

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
    }

    @Test
    void telemetryFailureCannotChangeASuccessfulCheckResult() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (id.getName().equals("baton.watch.check.duration")) {
                    throw new IllegalStateException("registry unavailable");
                }
                return id;
            }
        });
        CheckObservation expected = CheckObservation.forHttpStatus(
                204,
                Duration.ofMillis(17),
                0,
                0);
        MeteredUrlChecker checker = new MeteredUrlChecker(
                ignored -> expected,
                new MonitoringMetrics(registry));

        CheckObservation actual = checker.check(TARGET);

        assertSame(expected, actual);
    }
}
