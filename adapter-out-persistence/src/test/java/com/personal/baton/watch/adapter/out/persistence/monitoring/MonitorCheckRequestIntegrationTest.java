package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult.Status;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.service.RequestMonitorCheckService;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MonitorCheckRequestIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    private static final ResourceReference REFERENCE = new ResourceReference("resource:recheck");

    @Test
    void advancesOnlyTheScheduleAndKeepsTheCooldownAcrossServiceInstances() {
        synchronize(REFERENCE.value(), 7, "https://example.com/", BASE_TIME);
        postpone();
        var before = projection(REFERENCE.value());

        var first = requestAt(BASE_TIME);
        assertThat(first.status()).isEqualTo(Status.SCHEDULED);
        assertThat(first.nextCheckAt()).isEqualTo(BASE_TIME);
        assertThat(projection(REFERENCE.value()))
                .usingRecursiveComparison().ignoringFields("nextCheckAt").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM watch_attempt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM watch_health_change_event", Integer.class)).isZero();
        assertThat(requestAt(BASE_TIME.plusSeconds(1)).status()).isEqualTo(Status.ALREADY_SCHEDULED);

        // 완료 처리로 다음 일반 점검이 예약된 상태를 구성한다.
        postpone();
        var limited = requestAt(BASE_TIME.plusSeconds(29).plusMillis(500));
        assertThat(limited.status()).isEqualTo(Status.RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isEqualTo(1);
        assertThat(requestAt(BASE_TIME.plusSeconds(30)).status()).isEqualTo(Status.SCHEDULED);
    }

    @Test
    void mergesIntoAnActiveLeaseAndRejectsInactiveOrMissingMonitors() {
        assertThat(requestAt(BASE_TIME).status()).isEqualTo(Status.NOT_FOUND);
        synchronize(REFERENCE.value(), 7, "https://example.com/", BASE_TIME);
        var claim = claimOne();

        assertThat(requestAt(claim.claimedAt().plusSeconds(1)).status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(checkWorkPersistence.finalizeCheck(finalization(claim,
                CheckObservation.forHttpStatus(200, Duration.ofMillis(100), 0, 0),
                claim.claimedAt().plusSeconds(2), claim.claimedAt().plusSeconds(62))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);

        monitorPersistence.synchronize(SynchronizeMonitorCommand.inactive(REFERENCE, new SourceRevision(8)),
                claim.claimedAt().plusSeconds(3));
        assertThat(requestAt(claim.claimedAt().plusSeconds(4)).status()).isEqualTo(Status.INACTIVE);
        assertThat(projection(REFERENCE.value()).nextCheckAt()).isEmpty();
    }

    @Test
    void concurrentRequestsCreateOneSchedule() throws Exception {
        synchronize(REFERENCE.value(), 7, "https://example.com/", BASE_TIME);
        postpone();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Status> request = () -> {
                ready.countDown();
                assertThat(start.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return requestAt(BASE_TIME).status();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Status.SCHEDULED, Status.ALREADY_SCHEDULED);
        } finally {
            start.countDown();
        }
    }

    private MonitorCheckRequestResult requestAt(Instant now) {
        return new RequestMonitorCheckService(monitorPersistence, Clock.fixed(now, ZoneOffset.UTC))
                .requestCheck(REFERENCE);
    }

    private void postpone() {
        jdbc.update("UPDATE watch_monitor SET next_check_at = ? WHERE resource_reference = ?",
                databaseTime(BASE_TIME.plusSeconds(60)), REFERENCE.value());
    }
}
