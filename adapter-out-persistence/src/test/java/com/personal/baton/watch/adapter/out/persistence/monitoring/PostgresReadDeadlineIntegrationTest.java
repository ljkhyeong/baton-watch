package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.personal.baton.watch.domain.monitoring.ResourceReference;
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
                JdbcClient.create(boundedJdbc), newTransactionOperations());

        assertReadTimesOutWhileTableIsLocked(
                "LOCK TABLE watch_monitor IN ACCESS EXCLUSIVE MODE",
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
                "LOCK TABLE watch_health_change_event_backlog IN ACCESS EXCLUSIVE MODE",
                boundedReads::getBacklogSnapshot);

        assertThat(boundedReads.getBacklogSnapshot().pendingCount()).isZero();
    }

    private JdbcTemplate boundedJdbc() {
        JdbcTemplate boundedJdbc = new JdbcTemplate(testDataSource);
        boundedJdbc.setQueryTimeout(READ_TIMEOUT_SECONDS);
        return boundedJdbc;
    }

    private void assertReadTimesOutWhileTableIsLocked(
            String lockSql, Runnable read) throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = null;

        try {
            lockHolder = executor.submit(() -> holdExclusiveTableLock(
                    lockSql, lockAcquired, releaseLock));
            assertThat(lockAcquired.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Throwable failure = assertTimeout(
                    Duration.ofSeconds(3), () -> catchThrowable(read::run));

            assertSqlState(failure, "57014");
        } finally {
            releaseLock.countDown();
            if (lockHolder != null) {
                lockHolder.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            shutdownAndAwait(executor);
        }
    }

    private void holdExclusiveTableLock(
            String lockSql, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
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

}
