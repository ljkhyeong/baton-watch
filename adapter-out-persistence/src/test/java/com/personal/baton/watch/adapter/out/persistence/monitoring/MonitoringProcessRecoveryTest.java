package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.domain.monitoring.Health;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 JVM 강제 종료와 DB 리스 자연 만료를 사용한다. 운영 데이터는 받지 않는다. */
@Tag("process-recovery")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class MonitoringProcessRecoveryTest extends MonitoringPersistenceIntegrationTestSupport {

    @TempDir
    Path temporaryDirectory;

    @Test
    void recoversCheckAfterProcessDiesWithCommittedClaim() throws Exception {
        synchronize("process-recovery", 1, "https://recovery.example/check", BASE_TIME);
        UUID abandonedAttempt;
        try (var crashed = new WorkerProcess("check-block", "")) {
            crashed.awaitCheckpoint();
            abandonedAttempt = jdbc.queryForObject(
                    "SELECT lease_attempt_id FROM watch_monitor", UUID.class);
            assertThat(countRowsInTable(jdbc, "watch_result")).isZero();
            crashed.kill();
            assertThat(checkWorkPersistence.claimDueCheck(LEASE)).isEmpty();
        }
        try (var recovered = new WorkerProcess("check", "")) {
            recovered.awaitSuccess();
        }
        assertThat(countRowsInTable(jdbc, "watch_attempt")).isEqualTo(2);
        assertThat(countRowsInTable(jdbc, "watch_result")).isOne();
        assertThat(jdbc.queryForObject("SELECT attempt_id FROM watch_result", UUID.class))
                .isNotEqualTo(abandonedAttempt);
        assertThat(projection("process-recovery").health()).isEqualTo(Health.HEALTHY);
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isOne();
    }

    @Test
    void recoversDeliveryAfterProcessDiesBeforeSending() throws Exception {
        UUID eventId = createEvent();
        try (var receiver = new CallbackReceiver(false)) {
            try (var crashed = new WorkerProcess("delivery-block", receiver.endpoint())) {
                crashed.awaitCheckpoint();
                crashed.kill();
                assertPendingClaim();
                assertThat(receiver.received).isEmpty();
            }
            try (var recovered = new WorkerProcess("delivery", receiver.endpoint())) {
                recovered.awaitSuccess();
            }
            assertThat(receiver.received).singleElement()
                    .satisfies(received -> assertThat(received.eventId()).isEqualTo(eventId.toString()));
        }
        assertDeliveredOnce(eventId);
    }

    @Test
    void redeliversSameEventAfterReceiverAcceptsButProcessLosesResponse() throws Exception {
        UUID eventId = createEvent();
        try (var receiver = new CallbackReceiver(true)) {
            ReceivedDelivery first;
            try (var crashed = new WorkerProcess("delivery", receiver.endpoint())) {
                first = receiver.received.poll(10, TimeUnit.SECONDS);
                assertThat(first).isNotNull();
                assertThat(first.eventId()).isEqualTo(eventId.toString());
                crashed.kill();
                assertPendingClaim();
                receiver.releaseFirstResponse.countDown();
            }
            try (var recovered = new WorkerProcess("delivery", receiver.endpoint())) {
                recovered.awaitSuccess();
            }
            assertThat(receiver.received).containsExactly(first);
        }
        assertDeliveredOnce(eventId);
    }

    private UUID createEvent() {
        synchronize("process-recovery", 1, "https://recovery.example/check", BASE_TIME);
        var claim = claimOne();
        checkWorkPersistence.finalizeCheck(finalization(claim,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                claim.claimedAt(), claim.claimedAt().plus(INTERVAL)));
        return jdbc.queryForObject("SELECT event_id FROM watch_health_change_event", UUID.class);
    }

    private JdbcHealthChangeEventDeliveryAdapter deliveries() {
        return new JdbcHealthChangeEventDeliveryAdapter(JdbcClient.create(jdbc), newTransactionOperations());
    }

    private void assertPendingClaim() {
        assertThat(jdbc.queryForObject(
                "SELECT delivery_status FROM watch_health_change_event", String.class)).isEqualTo("PENDING");
        assertThat(deliveries().getBacklogSnapshot().pendingCount()).isOne();
        assertThat(deliveries().claimPendingEvent(Duration.ofSeconds(60))).isEmpty();
    }

    private void assertDeliveredOnce(UUID eventId) {
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT event_id, delivery_status, delivery_attempt, last_http_status_code,
                       delivery_lease_token, delivery_lease_expires_at
                FROM watch_health_change_event
                """))
                .containsEntry("event_id", eventId)
                .containsEntry("delivery_status", "DELIVERED")
                .containsEntry("delivery_attempt", 2)
                .containsEntry("last_http_status_code", 204)
                .containsEntry("delivery_lease_token", null)
                .containsEntry("delivery_lease_expires_at", null);
        assertThat(deliveries().getBacklogSnapshot().pendingCount()).isZero();
    }

    private final class WorkerProcess implements AutoCloseable {
        private final Path checkpoint;
        private final Path output;
        private final Process process;

        private WorkerProcess(String mode, String endpoint) throws IOException {
            Path directory = Files.createTempDirectory(temporaryDirectory, "worker-");
            checkpoint = directory.resolve("claimed");
            output = directory.resolve("output.log");
            var builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx128m", "-cp", System.getProperty("watch.recovery.classpath"),
                    MonitoringRecoveryProcess.class.getName(), mode, checkpoint.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("WATCH_RECOVERY_JDBC_URL", POSTGRES.getJdbcUrl());
            builder.environment().put("WATCH_RECOVERY_DB_USER", POSTGRES.getUsername());
            builder.environment().put("WATCH_RECOVERY_DB_PASSWORD", POSTGRES.getPassword());
            builder.environment().put("WATCH_RECOVERY_TIME", Instant.now().toString());
            builder.environment().put("WATCH_RECOVERY_CALLBACK", endpoint);
            process = builder.start();
        }

        private void awaitCheckpoint() {
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(checkpoint));
            assertThat(process.isAlive()).isTrue();
        }

        private void kill() throws InterruptedException {
            assertThat(process.isAlive()).isTrue();
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        }

        private void awaitSuccess() throws Exception {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("리스 만료 후 자식 JVM 완료").isTrue();
            assertThat(process.exitValue()).as("시험 JVM 출력: %s", Files.readString(output)).isZero();
        }

        @Override
        public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private record ReceivedDelivery(String eventId, String payload) {}

    private static final class CallbackReceiver implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        private final LinkedBlockingQueue<ReceivedDelivery> received = new LinkedBlockingQueue<>();
        private final CountDownLatch releaseFirstResponse = new CountDownLatch(1);

        private CallbackReceiver(boolean loseFirstResponse) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            var first = new AtomicBoolean(loseFirstResponse);
            server.createContext("/callback", exchange -> {
                try (exchange) {
                    received.add(new ReceivedDelivery(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    if (first.compareAndSet(true, false)) {
                        try {
                            releaseFirstResponse.await(15, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return;
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        }

        @Override
        public void close() throws InterruptedException {
            releaseFirstResponse.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
