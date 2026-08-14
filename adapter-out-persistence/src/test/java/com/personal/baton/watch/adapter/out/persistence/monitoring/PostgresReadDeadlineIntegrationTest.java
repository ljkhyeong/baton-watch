package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PostgresReadDeadlineIntegrationTest
        extends MonitoringPersistenceIntegrationTestSupport {

    private static final int READ_TIMEOUT_SECONDS = 1;

    @Test
    void boundsTheNonTransactionalProjectionRead() throws Exception {
        JdbcTemplate boundedJdbc = boundedJdbc();
        JdbcMonitorPersistenceAdapter boundedReads = new JdbcMonitorPersistenceAdapter(
                boundedJdbc, newTransactionOperations());

        assertReadTimesOutWhileTableIsLocked(
                "watch_monitor",
                () -> boundedReads.findProjection(new ResourceReference("resource:read-timeout")));

        assertThat(boundedReads.findProjection(new ResourceReference("resource:read-timeout")))
                .isEmpty();
    }

    @Test
    void boundsTheJdbcClientBacklogReadThroughTheSharedJdbcTemplate() throws Exception {
        JdbcTemplate boundedJdbc = boundedJdbc();
        JdbcHealthChangeEventDeliveryAdapter boundedReads =
                new JdbcHealthChangeEventDeliveryAdapter(
                        JdbcClient.create(boundedJdbc), newTransactionOperations());

        assertReadTimesOutWhileTableIsLocked(
                "watch_health_change_event", boundedReads::getBacklogSnapshot);

        assertThat(boundedReads.getBacklogSnapshot().pendingCount()).isZero();
    }

    private JdbcTemplate boundedJdbc() {
        JdbcTemplate boundedJdbc = new JdbcTemplate(testDataSource);
        boundedJdbc.setQueryTimeout(READ_TIMEOUT_SECONDS);
        return boundedJdbc;
    }

    private void assertReadTimesOutWhileTableIsLocked(
            String table, Runnable read) throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = null;

        try {
            lockHolder = executor.submit(() -> holdExclusiveTableLock(
                    table, lockAcquired, releaseLock));
            assertThat(lockAcquired.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(read::run);

            assertSqlState(failure, "57014");
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(3));
        } finally {
            releaseLock.countDown();
            if (lockHolder != null) {
                lockHolder.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            shutdownAndAwait(executor);
        }
    }

    private void holdExclusiveTableLock(
            String table, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
        String lockSql = switch (table) {
            case "watch_monitor" -> "LOCK TABLE watch_monitor IN ACCESS EXCLUSIVE MODE";
            case "watch_health_change_event" ->
                "LOCK TABLE watch_health_change_event IN ACCESS EXCLUSIVE MODE";
            default -> throw new IllegalArgumentException("unsupported lock table");
        };
        TransactionTemplate holder = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        holder.executeWithoutResult(status -> {
            new JdbcTemplate(testDataSource).execute(lockSql);
            lockAcquired.countDown();
            await(releaseLock);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while holding the read-deadline test lock", exception);
        }
    }

    private static void assertSqlState(Throwable failure, String expectedState) {
        assertThat(failure).isNotNull();
        assertThat(failure).rootCause()
                .isInstanceOfSatisfying(SQLException.class, sqlFailure ->
                        assertThat(sqlFailure.getSQLState()).isEqualTo(expectedState));
    }
}
