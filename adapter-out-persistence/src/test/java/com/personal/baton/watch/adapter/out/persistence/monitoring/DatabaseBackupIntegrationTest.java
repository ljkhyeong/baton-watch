package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class DatabaseBackupIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    @TempDir
    Path temporary;

    @Test
    void restoresMonitorResultAndPendingRetryWithoutOverwritingArchive() throws Exception {
        synchronize("backup-fixture", 1, "https://backup.example/private-query?token=fixture", BASE_TIME);
        var check = claimOne();
        checkWorkPersistence.finalizeCheck(finalization(check,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                check.claimedAt(), check.claimedAt().plus(INTERVAL)));
        var deliveries = new JdbcHealthChangeEventDeliveryAdapter(
                JdbcClient.create(jdbc), newTransactionOperations());
        var event = deliveries.claimPendingEvent(LEASE).orElseThrow();
        deliveries.finalizeDelivery(new EventDeliveryFinalization(
                event.payload().eventId(), event.leaseToken(),
                EventDeliveryObservation.forHttpStatus(503), event.claimedAt(),
                event.claimedAt().plusSeconds(5)));

        Path archive = temporary.resolve("database.dump");
        assertThat(runTool("create", POSTGRES.getContainerId(), archive.toString()).status()).isZero();
        assertThat(Files.getPosixFilePermissions(archive))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        byte[] original = Files.readAllBytes(archive);
        assertThat(runTool("create", POSTGRES.getContainerId(), archive.toString()).status()).isNotZero();
        assertThat(Files.readAllBytes(archive)).isEqualTo(original);

        var restored = runTool("verify", archive.toString());
        assertThat(restored.status()).as(restored.output()).isZero();
        assertThat(restored.output())
                .contains("복원 확인: 모니터=1 시도=1 결과=1 대기이벤트=1 완료이벤트=0 전달시도합계=1")
                .doesNotContain("backup-fixture", "backup.example", "token=fixture");
        assertThat(deliveries.getBacklogSnapshot().pendingCount()).isOne();
    }

    @Test
    void rejectsRestoredBacklogThatDoesNotMatchEvents() throws Exception {
        jdbc.update("""
                UPDATE watch_health_change_event_backlog
                SET pending_count = 1, oldest_changed_at = transaction_timestamp()
                """);
        Path archive = temporary.resolve("inconsistent.dump");
        assertThat(runTool("create", POSTGRES.getContainerId(), archive.toString()).status()).isZero();
        var inconsistent = runTool("verify", archive.toString());
        assertThat(inconsistent.status()).isNotZero();
        assertThat(inconsistent.output()).contains("복원 데이터 확인 실패");
    }

    @Test
    void rejectsCorruptArchiveAndFailedDumpWithoutLeavingOutput() throws Exception {
        Path archive = temporary.resolve("corrupt.dump");
        Files.writeString(archive, "손상된 시험 백업 token=fixture");
        var corrupted = runTool("verify", archive.toString());
        assertThat(corrupted.status()).isNotZero();
        assertThat(corrupted.output()).contains("복원 실패").doesNotContain("token=fixture");

        Path failed = temporary.resolve("failed.dump");
        assertThat(runTool("create", "watch-missing-container-" + System.nanoTime(), failed.toString())
                .status()).isNotZero();
        assertThat(failed).doesNotExist();
    }

    private ToolResult runTool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", "../ops/staging-database-backup.sh"));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(temporary, "backup-tool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("백업 도구 종료").isTrue();
            return new ToolResult(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private record ToolResult(int status, String output) {}
}
