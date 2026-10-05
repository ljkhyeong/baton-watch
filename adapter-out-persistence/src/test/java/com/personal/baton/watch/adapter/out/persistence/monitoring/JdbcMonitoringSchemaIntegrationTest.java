package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcMonitoringSchemaIntegrationTest extends PostgresPersistenceIntegrationTestSupport {

    @Test
    void migrationsCreateMetadataOnlyTablesWithDomainBounds() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "watch_attempt",
                "watch_health_change_event",
                "watch_health_change_event_backlog",
                "watch_monitor",
                "watch_result");
        assertThat(characterMaximum("watch_monitor", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_monitor", "target_url")).isEqualTo(2048);
        assertThat(characterMaximum("watch_attempt", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_attempt", "target_url")).isEqualTo(2048);

        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                """, String.class);
        assertThat(columns)
                .noneMatch(name -> name.contains("body"))
                .noneMatch(name -> name.contains("resolved"))
                .noneMatch(name -> name.contains("exception"))
                .noneMatch(name -> name.contains("header"))
                .noneMatch(name -> name.contains("cookie"));
    }

    @Test
    void databaseRejectsMonitorStateAndHealthMismatches() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at, created_at, updated_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'UNKNOWN', 0, ?, ?, ?)
                """,
                "resource:inactive-with-next-check",
                databaseTime(BASE_TIME),
                databaseTime(BASE_TIME),
                databaseTime(BASE_TIME)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at, created_at, updated_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'HEALTHY', 1, NULL, ?, ?)
                """,
                "resource:healthy-with-failure",
                databaseTime(BASE_TIME),
                databaseTime(BASE_TIME)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void maintenanceIndexesMatchTheClaimAndRetentionAccessPaths() {
        assertThat(indexDefinition("ix_watch_health_event_delivery_due"))
                .contains("(next_attempt_at, changed_at, event_id)")
                .contains("INCLUDE (delivery_lease_expires_at)")
                .contains("delivery_status")
                .contains("'PENDING'");
        assertThat(indexDefinition("ix_watch_monitor_lease_attempt"))
                .contains("(lease_attempt_id)")
                .contains("lease_attempt_id IS NOT NULL");
    }

    @Test
    void backlogTriggerFunctionRestrictsItsExecutionContext() {
        var function = jdbc.queryForMap("""
                SELECT procedure.prosecdef,
                       array_to_string(procedure.proconfig, ',') AS configuration
                FROM pg_proc procedure
                JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'public'
                  AND procedure.proname = 'maintain_watch_health_change_event_backlog'
                """);
        Integer publicExecuteGrants = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.routine_privileges
                WHERE routine_schema = 'public'
                  AND routine_name = 'maintain_watch_health_change_event_backlog'
                  AND grantee = 'PUBLIC'
                  AND privilege_type = 'EXECUTE'
                """, Integer.class);

        assertThat(function)
                .containsEntry("prosecdef", true)
                .containsEntry("configuration", "search_path=pg_catalog, pg_temp");
        assertThat(publicExecuteGrants).isZero();
    }

    @Test
    void deliveryLeaseUpdatesDoNotWaitForTheBacklogSummaryLock() throws Exception {
        String reference = "resource:backlog-unrelated-update";
        insertInactiveMonitor(reference);
        UUID eventId = insertPendingEvent(reference, BASE_TIME);

        withLockHeld(
                () -> jdbc.queryForObject("""
                        SELECT pending_count
                        FROM watch_health_change_event_backlog
                        WHERE singleton
                        FOR UPDATE
                        """, Long.class),
                () -> {
                    Integer updated = transactionTemplate().execute(ignored -> {
                        jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                        return jdbc.update("""
                                UPDATE watch_health_change_event
                                SET delivery_attempt = delivery_attempt + 1
                                WHERE event_id = ?
                                """, eventId);
                    });
                    assertThat(updated).isEqualTo(1);
                });
    }

    @Test
    void backlogSummaryRemainsExactAfterWaitingForAConcurrentTransition() throws Exception {
        String reference = "resource:backlog-concurrency";
        Instant firstChangedAt = BASE_TIME;
        Instant insertedChangedAt = BASE_TIME.plusSeconds(1);
        Instant lastChangedAt = BASE_TIME.plusSeconds(2);
        insertInactiveMonitor(reference);
        UUID firstEvent = insertPendingEvent(reference, firstChangedAt);
        insertPendingEvent(reference, lastChangedAt);

        JdbcTemplate insertingJdbc = new JdbcTemplate(testDataSource);
        JdbcTemplate completingJdbc = new JdbcTemplate(testDataSource);
        TransactionTemplate insertingTransaction = transactionTemplate();
        TransactionTemplate completingTransaction = transactionTemplate();
        CountDownLatch insertHoldsSummaryLock = new CountDownLatch(1);
        CountDownLatch allowInsertCommit = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        AtomicInteger completingBackend = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> insert = null;
        Future<?> completion = null;
        try {
            insert = executor.submit(() -> insertingTransaction.executeWithoutResult(ignored -> {
                insertPendingEvent(insertingJdbc, reference, insertedChangedAt);
                insertHoldsSummaryLock.countDown();
                awaitLatch(allowInsertCommit);
            }));
            assertThat(insertHoldsSummaryLock.await(
                    CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            completion = executor.submit(() -> completingTransaction.executeWithoutResult(ignored -> {
                completingBackend.set(completingJdbc.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                completionStarted.countDown();
                completingJdbc.update("""
                        UPDATE watch_health_change_event
                        SET delivery_status = 'DELIVERED',
                            delivery_attempt = 1,
                            next_attempt_at = NULL,
                            delivered_at = ?,
                            last_delivery_outcome = 'DELIVERED',
                            last_http_status_code = 204
                        WHERE event_id = ?
                        """, databaseTime(firstChangedAt.plusSeconds(10)), firstEvent);
            }));
            assertThat(completionStarted.await(
                    CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            awaitLockWait(completingBackend.get());

            allowInsertCommit.countDown();
            insert.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            completion.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var actual = jdbc.queryForMap("""
                    SELECT COUNT(*) AS pending_count, MIN(changed_at) AS oldest_changed_at
                    FROM watch_health_change_event
                    WHERE delivery_status = 'PENDING'
                    """);
            var summary = jdbc.queryForMap("""
                    SELECT pending_count, oldest_changed_at
                    FROM watch_health_change_event_backlog
                    WHERE singleton
                    """);
            assertThat(summary).isEqualTo(actual);
            assertThat(((Timestamp) summary.get("oldest_changed_at")).toInstant())
                    .isEqualTo(insertedChangedAt);
        } finally {
            allowInsertCommit.countDown();
            cancelIfRunning(insert);
            cancelIfRunning(completion);
            shutdownAndAwait(executor);
        }
    }

    private int characterMaximum(String table, String column) {
        return jdbc.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
    }

    private String indexDefinition(String indexName) {
        return jdbc.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """, String.class, indexName);
    }

    private void insertInactiveMonitor(String reference) {
        jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at, created_at, updated_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'UNKNOWN', 0, NULL, ?, ?)
                """, reference, databaseTime(BASE_TIME), databaseTime(BASE_TIME));
    }

    private UUID insertPendingEvent(String reference, Instant changedAt) {
        return insertPendingEvent(jdbc, reference, changedAt);
    }

    private UUID insertPendingEvent(JdbcTemplate targetJdbc, String reference, Instant changedAt) {
        UUID eventId = UUID.randomUUID();
        targetJdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at, next_attempt_at
                ) VALUES (?, ?, 1, NULL, 'UNKNOWN', 'HEALTHY', ?, ?)
                """, eventId, reference, databaseTime(changedAt), databaseTime(changedAt));
        return eventId;
    }

    private void awaitLockWait(int backendPid) {
        Awaitility.await("백로그 요약 행 잠금 대기")
                .pollDelay(0, TimeUnit.MILLISECONDS)
                .pollInterval(20, TimeUnit.MILLISECONDS)
                .atMost(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        SELECT wait_event_type = 'Lock'
                        FROM pg_stat_activity
                        WHERE pid = ?
                        """, Boolean.class, backendPid)).isTrue());
    }
}
