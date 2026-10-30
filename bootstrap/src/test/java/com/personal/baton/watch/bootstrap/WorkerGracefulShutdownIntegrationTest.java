package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.watch.adapter.out.external.check.ApacheUrlChecker;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 운영 조립을 실제로 닫아 실행 중 항목은 완료되고 같은 배치의 다음 항목은 점유되지 않는지 확인한다.
 * 시험이 컨텍스트를 직접 닫으므로 Spring Test의 컨텍스트 캐시를 쓰지 않는다.
 */
@Testcontainers
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class WorkerGracefulShutdownIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = BootstrapTestFixtures.postgres("graceful-shutdown-test");

    @Test
    void finishesTheRunningItemWithoutClaimingTheNextOneAfterTheShutdownSignal() throws Exception {
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch releaseCheck = new CountDownLatch(1);
        ApacheUrlChecker checker = mock(ApacheUrlChecker.class);
        when(checker.check(any())).thenAnswer(ignored -> {
            checkStarted.countDown();
            assertThat(releaseCheck.await(30, TimeUnit.SECONDS)).isTrue();
            return CheckObservation.forHttpStatus(200, Duration.ZERO, 0);
        });
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BatonWatchApplication.class)
                .initializers(replacingUrlChecker(checker))
                .run(
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--watch.api-token=graceful-shutdown-monitor-token-0123456789",
                        "--watch.poll-interval=1s",
                        "--watch.maintenance-interval=1d",
                        "--watch.check-batch-size=2");
        try (context) {
            ThreadPoolTaskScheduler monitoring = context.getBean(
                    WorkerSchedulingConfiguration.MONITORING_TASK_SCHEDULER, ThreadPoolTaskScheduler.class);
            SynchronizeMonitorUseCase synchronizeMonitor = context.getBean(SynchronizeMonitorUseCase.class);
            for (String reference : List.of("graceful-shutdown-first", "graceful-shutdown-second")) {
                synchronizeMonitor.synchronize(SynchronizeMonitorCommand.active(
                        new ResourceReference(reference),
                        new SourceRevision(1),
                        new TargetUrl("https://graceful-shutdown.example/" + reference)));
            }
            assertThat(checkStarted.await(30, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> closed = CompletableFuture.runAsync(context::close);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> monitoring.getScheduledThreadPoolExecutor().isShutdown());
            releaseCheck.countDown();
            closed.get(90, TimeUnit.SECONDS);
        } finally {
            releaseCheck.countDown();
        }

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                ResultSet rows = connection.createStatement().executeQuery("""
                        SELECT (SELECT count(*) FROM watch_attempt) AS attempts,
                               (SELECT count(*) FROM watch_result WHERE outcome = 'SUCCESS') AS results
                        """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt("attempts")).as("종료 신호 뒤 새로 점유한 시도가 없어야 한다").isOne();
            assertThat(rows.getInt("results")).as("실행 중 항목은 정상 결과로 완료되어야 한다").isOne();
        }
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> replacingUrlChecker(
            ApacheUrlChecker checker) {
        return context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof ApacheUrlChecker ? checker : bean;
            }
        });
    }
}
