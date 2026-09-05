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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest(name = "{0} 실행 중 종료하면 CLI 하위 프로세스와 임시 파일 정리")
    @ValueSource(strings = {"create", "verify"})
    void terminatesBlockedCommandAndCleansTemporaryResources(String operation) throws Exception {
        Path commands = Files.createDirectory(temporary.resolve("commands"));
        Path work = Files.createDirectory(temporary.resolve("work"));
        Path ready = temporary.resolve("ready");
        Path cleaned = temporary.resolve("cleaned");
        Path docker = commands.resolve("docker");
        Files.writeString(docker, """
                #!/usr/bin/env bash
                set -eu
                case " $* " in
                    *" up "*) exit 0 ;;
                    *" down "*) touch "$WATCH_TEST_CLEANED"; exit 0 ;;
                    *" exec "*)
                        sleep 300 &
                        child=$!
                        printf '부분 시험 출력\\n'
                        printf '%s %s\\n' "$$" "$child" > "$WATCH_TEST_READY"
                        wait "$child"
                        ;;
                    *) exit 1 ;;
                esac
                """);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        Path archive = temporary.resolve("interrupted.dump");
        if (operation.equals("verify")) {
            Files.writeString(archive, "중단 시험용 입력");
        }
        Path output = temporary.resolve("interrupted.log");
        ProcessBuilder builder = operation.equals("create")
                ? toolCommand(output, operation, "test-source", archive.toString())
                : toolCommand(output, operation, archive.toString());
        builder.environment().put("PATH", commands + ":" + System.getenv("PATH"));
        builder.environment().put("TMPDIR", work.toString());
        builder.environment().put("WATCH_TEST_READY", ready.toString());
        builder.environment().put("WATCH_TEST_CLEANED", cleaned.toString());
        List<ProcessHandle> children = new ArrayList<>();
        Process process = builder.start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> Files.exists(ready) && Files.size(ready) > 0);
            for (String pid : Files.readString(ready).trim().split(" ")) {
                children.add(ProcessHandle.of(Long.parseLong(pid)).orElseThrow());
            }
            process.destroy();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("SIGTERM 뒤 정리 종료").isTrue();
            assertThat(process.exitValue()).isEqualTo(143);
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> children.stream().noneMatch(ProcessHandle::isAlive));
            assertThat(work).isEmptyDirectory();
            if (operation.equals("verify")) {
                assertThat(cleaned).exists();
                assertThat(archive).hasContent("중단 시험용 입력");
            } else {
                assertThat(cleaned).doesNotExist();
                assertThat(archive).doesNotExist();
                try (var files = Files.list(temporary)) {
                    assertThat(files).noneMatch(path -> path.getFileName().toString().startsWith(".watch-backup."));
                }
            }
        } finally {
            stopTool(process);
            children.forEach(ProcessHandle::destroyForcibly);
        }
    }

    private ProcessBuilder toolCommand(Path output, String... arguments) {
        List<String> command = new ArrayList<>(List.of("bash", "../ops/staging-database-backup.sh"));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    }

    private ToolResult runTool(String... arguments) throws Exception {
        Path output = Files.createTempFile(temporary, "backup-tool-", ".log");
        Process process = toolCommand(output, arguments).start();
        try {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("백업 도구 종료").isTrue();
            return new ToolResult(process.exitValue(), Files.readString(output));
        } finally {
            stopTool(process);
        }
    }

    private static void stopTool(Process process) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().toList().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("백업 도구 강제 종료").isTrue();
        }
    }

    private record ToolResult(int status, String output) {}
}
