package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.awaitility.Awaitility.await;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.service.EventDeliveryRetryPolicy;
import com.personal.baton.watch.application.monitoring.service.RunDueChecksService;
import com.personal.baton.watch.application.monitoring.service.RunEventDeliveriesService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 복구 시험만 사용하는 자식 JVM 진입점. 운영 설정이나 아웃바운드 어댑터를 바꾸지 않는다. */
public final class MonitoringRecoveryProcess {

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        Path checkpoint = Path.of(arguments[1]);
        var dataSource = new DriverManagerDataSource(
                System.getenv("WATCH_RECOVERY_JDBC_URL"),
                System.getenv("WATCH_RECOVERY_DB_USER"),
                System.getenv("WATCH_RECOVERY_DB_PASSWORD"));
        var driverProperties = new Properties();
        driverProperties.setProperty("connectTimeout", "3");
        driverProperties.setProperty("socketTimeout", "10");
        dataSource.setConnectionProperties(driverProperties);
        var template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(5);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(5);
        var transactions = new PostgresTransactionOperations(template, transaction, Duration.ofSeconds(1));
        var jdbc = JdbcClient.create(template);
        Clock clock = Clock.fixed(Instant.parse(System.getenv("WATCH_RECOVERY_TIME")), ZoneOffset.UTC);

        if (mode.startsWith("check")) {
            var worker = new RunDueChecksService(
                    new JdbcCheckWorkPersistenceAdapter(jdbc, transactions), target -> {
                        assertOutsideTransaction();
                        if (mode.equals("check-block")) {
                            blockAfterClaim(checkpoint);
                        }
                        return CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0);
                    }, clock, Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(30), 1);
            await().pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(80))
                    .until(() -> worker.runDueChecks().applied() == 1);
            return;
        }

        // 로컬 수신 대역과의 통신만 담당한다. 운영 HTTPS·DNS 정책 시험을 대신하지 않는다.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var worker = new RunEventDeliveriesService(
                    new JdbcHealthChangeEventDeliveryAdapter(jdbc, transactions), payload -> {
                        assertOutsideTransaction();
                        if (mode.equals("delivery-block")) {
                            blockAfterClaim(checkpoint);
                        }
                        HttpRequest request = HttpRequest.newBuilder(
                                        URI.create(System.getenv("WATCH_RECOVERY_CALLBACK")))
                                .timeout(Duration.ofSeconds(65))
                                .header("Idempotency-Key", payload.eventId().toString())
                                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                                .build();
                        try {
                            return EventDeliveryObservation.forHttpStatus(
                                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("복구 시험 전달이 중단됐습니다", exception);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    }, clock, Duration.ofSeconds(60),
                    new EventDeliveryRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15)), 1);
            await().pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(80))
                    .until(() -> worker.runEventDeliveries().delivered() == 1);
        }
    }

    private static void blockAfterClaim(Path checkpoint) {
        try {
            Files.createFile(checkpoint);
            // 부모가 강제 종료할 때까지 확정하지 않는다. 부모가 사라져도 JVM을 남기지 않는다.
            System.in.read();
            Runtime.getRuntime().halt(2);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void assertOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AssertionError("외부 호출 전에 점유 트랜잭션이 끝나야 합니다");
        }
    }
}
