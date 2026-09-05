package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.service.EventDeliveryRetryPolicy;
import com.personal.baton.watch.application.monitoring.service.RunDueChecksService;
import com.personal.baton.watch.application.monitoring.service.RunEventDeliveriesService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 외부 URL이나 운영 DB를 입력받지 않는 단일 실행 레인 부하·복구 시험이다. */
@Tag("load")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class MonitoringLoadRecoveryTest extends MonitoringPersistenceIntegrationTestSupport {

    @ParameterizedTest(name = "점검 지연 {0}ms에서 DB 잠금과 콜백 장애 후 백로그 복구")
    @ValueSource(ints = {0, 20})
    void drainsWorkAfterDatabaseAndCallbackRecovery(int checkDelayMillis) throws Exception {
        int monitors = Integer.parseInt(System.getProperty("watch.load.monitors", "100"));
        assertThat(monitors).as("시험 모니터 수: 1~1000").isBetween(1, 1000);
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        long started = System.nanoTime();
        for (int index = 0; index < monitors; index++) {
            synchronize("load-" + index, 1, "https://load.example/check", clock.instant());
        }
        long synchronizedAt = System.nanoTime();

        jdbc.setQueryTimeout(2);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        transaction.setTimeout(2);
        var transactions = new PostgresTransactionOperations(jdbc, transaction, Duration.ofMillis(150));
        var checks = new JdbcCheckWorkPersistenceAdapter(JdbcClient.create(jdbc), transactions);
        var deliveries = new JdbcHealthChangeEventDeliveryAdapter(JdbcClient.create(jdbc), transactions);
        var worker = new RunDueChecksService(checks, ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try {
                Thread.sleep(Duration.ofMillis(checkDelayMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("부하 시험이 중단됐습니다", exception);
            }
            return CheckObservation.forHttpStatus(200, Duration.ofMillis(checkDelayMillis), 0, 0);
        }, clock, LEASE, Duration.ofDays(1), Duration.ofSeconds(30), 1);

        // 완료 처리의 백로그 트리거만 막는다. 이미 커밋한 점유는 리스 회수로 복구해야 한다.
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(new DataSourceTransactionManager(testDataSource))
                    .executeWithoutResult(ignored -> {
                        jdbc.queryForObject(
                                "SELECT singleton FROM watch_health_change_event_backlog FOR UPDATE",
                                Boolean.class);
                        var blocked = executor.submit(worker::runDueChecks);
                        assertThatThrownBy(() -> blocked.get(10, TimeUnit.SECONDS))
                                .satisfies(failure -> assertSqlState(failure, "55P03"));
                    });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM watch_result", Integer.class)).isZero();
        assertThat(jdbc.update("""
                UPDATE watch_monitor
                SET lease_expires_at = transaction_timestamp() - INTERVAL '1 second'
                WHERE lease_token IS NOT NULL
                """)).isOne();

        Duration maximumDelay = Duration.ZERO;
        for (int index = 0; index < monitors; index++) {
            Duration currentDelay = checks.getOldestDueCheckDelay();
            if (currentDelay.compareTo(maximumDelay) > 0) {
                maximumDelay = currentDelay;
            }
            var result = worker.runDueChecks();
            assertThat(result.applied()).isOne();
        }
        assertThat(worker.runDueChecks().claimed()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM watch_attempt", Integer.class))
                .isEqualTo(monitors + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM watch_result", Integer.class))
                .isEqualTo(monitors);
        assertThat(deliveries.getBacklogSnapshot().pendingCount()).isEqualTo(monitors);
        long checkedAt = System.nanoTime();

        List<HealthChangeEventPayload> failedPayloads = new ArrayList<>();
        var failingSender = deliveryWorker(deliveries, payload -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            failedPayloads.add(payload);
            return EventDeliveryObservation.forHttpStatus(503);
        }, clock);
        for (int index = 0; index < monitors; index++) {
            assertThat(failingSender.runEventDeliveries().retryScheduled()).isOne();
        }
        assertThat(failingSender.runEventDeliveries().claimed()).isZero();
        assertThat(deliveries.getBacklogSnapshot().pendingCount()).isEqualTo(monitors);

        // 긴 실제 대기 대신 이 격리 DB에서만 재시도 시각을 앞으로 당긴다.
        jdbc.update("UPDATE watch_health_change_event SET next_attempt_at = changed_at");
        List<HealthChangeEventPayload> deliveredPayloads = new ArrayList<>();
        var recoveredSender = deliveryWorker(deliveries, payload -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            deliveredPayloads.add(payload);
            return EventDeliveryObservation.forHttpStatus(204);
        }, clock);
        for (int index = 0; index < monitors; index++) {
            assertThat(recoveredSender.runEventDeliveries().delivered()).isOne();
        }
        assertThat(deliveredPayloads).containsExactlyInAnyOrderElementsOf(failedPayloads);
        assertThat(deliveries.getBacklogSnapshot().pendingCount()).isZero();
        assertThat(deliveries.getBacklogSnapshot().oldestChangedAt()).isEmpty();
        assertThat(recoveredSender.runEventDeliveries().claimed()).isZero();
        System.out.printf(
                "부하·복구 결과: 모니터=%d 지연=%dms 동기화=%dms 점검·DB복구=%dms "
                        + "전달실패·복구=%dms 최대적체지연=%dms 최종백로그=0%n",
                monitors, checkDelayMillis, millis(synchronizedAt - started),
                millis(checkedAt - synchronizedAt), millis(System.nanoTime() - checkedAt),
                maximumDelay.toMillis());
    }

    private RunEventDeliveriesService deliveryWorker(
            JdbcHealthChangeEventDeliveryAdapter deliveries, HealthChangeEventSender sender, Clock clock) {
        return new RunEventDeliveriesService(deliveries, sender, clock, LEASE,
                new EventDeliveryRetryPolicy(Duration.ofMinutes(5), Duration.ofMinutes(15)), 1);
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
