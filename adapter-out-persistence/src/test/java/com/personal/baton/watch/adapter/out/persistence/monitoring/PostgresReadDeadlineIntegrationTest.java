package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

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
        withLockHeld(() -> jdbc.execute(lockSql), () -> {
            Throwable failure = assertTimeout(
                    Duration.ofSeconds(3), () -> catchThrowable(read::run));

            assertSqlState(failure, "57014");
        });
    }

}
