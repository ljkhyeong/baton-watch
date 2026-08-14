package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

class PostgresTransactionOperationsIntegrationTest
        extends MonitoringPersistenceIntegrationTestSupport {

    private static final Duration TEST_LOCK_TIMEOUT = Duration.ofMillis(250);

    @Test
    void failsFastWhenAnotherTransactionHoldsTheMonitorRowLock() throws Exception {
        String reference = "resource:lock-timeout";
        synchronize(reference, 1, "https://lock-timeout.example/path", BASE_TIME);
        JdbcMonitorPersistenceAdapter boundedPersistence = new JdbcMonitorPersistenceAdapter(
                jdbc, boundedTransactions(Duration.ofSeconds(5), TEST_LOCK_TIMEOUT));
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = null;

        try {
            lockHolder = executor.submit(() -> holdMonitorLock(reference, lockAcquired, releaseLock));
            assertThat(lockAcquired.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> boundedPersistence.synchronize(
                    SynchronizeMonitorCommand.active(
                            new ResourceReference(reference),
                            new SourceRevision(2),
                            new TargetUrl("https://updated.example/path")),
                    BASE_TIME.plusSeconds(1)));

            assertSqlState(failure, "55P03");
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(2));
            assertThat(projection(reference).sourceRevision().value()).isEqualTo(1);
        } finally {
            releaseLock.countDown();
            if (lockHolder != null) {
                lockHolder.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            shutdownAndAwait(executor);
        }

        assertThat(boundedPersistence.synchronize(
                        SynchronizeMonitorCommand.active(
                                new ResourceReference(reference),
                                new SourceRevision(2),
                                new TargetUrl("https://updated.example/path")),
                        BASE_TIME.plusSeconds(1))
                .projection()
                .sourceRevision()
                .value())
                .isEqualTo(2);
    }

    @Test
    void rollsBackAllWritesWhenTheTotalTransactionTimeoutExpires() {
        String reference = "resource:transaction-timeout";
        TransactionOperations transactions = boundedTransactions(
                Duration.ofSeconds(1), TEST_LOCK_TIMEOUT);

        Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO watch_monitor (
                        resource_reference,
                        source_revision,
                        monitor_status,
                        target_url,
                        current_health,
                        consecutive_failures,
                        next_check_at,
                        created_at,
                        updated_at
                    ) VALUES (?, 0, 'INACTIVE', NULL, 'UNKNOWN', 0, NULL, ?, ?)
                    """, reference, databaseTime(BASE_TIME), databaseTime(BASE_TIME));
            jdbc.execute("SELECT pg_sleep(3)");
        }));

        assertSqlState(failure, "57014");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM watch_monitor WHERE resource_reference = ?",
                Integer.class,
                reference))
                .isZero();
    }

    @Test
    void keepsLockTimeoutLocalToTheOwnedPhysicalConnection() throws SQLException {
        SingleConnectionDataSource reusedConnection = new SingleConnectionDataSource(
                testDataSource.getConnection(), true);
        JdbcTemplate reusedJdbc = new JdbcTemplate(reusedConnection);
        TransactionTemplate delegate = new TransactionTemplate(
                new DataSourceTransactionManager(reusedConnection));
        delegate.setTimeout(5);
        TransactionOperations transactions = new PostgresTransactionOperations(
                reusedJdbc, delegate, TEST_LOCK_TIMEOUT);

        try {
            String inside = transactions.execute(status ->
                    reusedJdbc.queryForObject("SHOW lock_timeout", String.class));

            assertThat(inside).isEqualTo("250ms");
            assertThat(reusedJdbc.queryForObject("SHOW lock_timeout", String.class))
                    .isEqualTo("0");
        } finally {
            reusedConnection.destroy();
        }
    }

    @Test
    void rejectsAnOuterTransactionBeforePersistenceWorkStarts() {
        TransactionOperations transactions = boundedTransactions(
                Duration.ofSeconds(5), TEST_LOCK_TIMEOUT);
        TransactionTemplate outer = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        AtomicBoolean persistenceWorkStarted = new AtomicBoolean();

        Throwable failure = catchThrowable(() -> outer.executeWithoutResult(status ->
                transactions.executeWithoutResult(innerStatus -> persistenceWorkStarted.set(true))));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not join an existing transaction");
        assertThat(persistenceWorkStarted).isFalse();
    }

    private TransactionOperations boundedTransactions(
            Duration transactionTimeout, Duration lockTimeout) {
        TransactionTemplate delegate = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        delegate.setTimeout(Math.toIntExact(transactionTimeout.toSeconds()));
        return new PostgresTransactionOperations(jdbc, delegate, lockTimeout);
    }

    private void holdMonitorLock(
            String reference, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
        TransactionTemplate holder = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        holder.executeWithoutResult(status -> {
            new JdbcTemplate(testDataSource).queryForObject(
                    "SELECT resource_reference FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                    String.class,
                    reference);
            lockAcquired.countDown();
            await(releaseLock);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding database test lock", exception);
        }
    }

    private static void assertSqlState(Throwable failure, String expectedState) {
        assertThat(failure).isNotNull();
        assertThat(failure).rootCause()
                .isInstanceOfSatisfying(SQLException.class, sqlFailure ->
                        assertThat(sqlFailure.getSQLState()).isEqualTo(expectedState));
    }
}
