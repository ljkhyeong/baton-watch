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

    @Test
    void boundsTheNonTransactionalProjectionRead() throws Exception {
        JdbcTemplate boundedJdbc = new JdbcTemplate(testDataSource);
        boundedJdbc.setQueryTimeout(1);
        JdbcMonitorPersistenceAdapter boundedReads = new JdbcMonitorPersistenceAdapter(
                JdbcClient.create(boundedJdbc), transactionTemplate());
        ResourceReference reference = new ResourceReference("resource:read-timeout");

        withLockHeld(() -> jdbc.execute("LOCK TABLE watch_monitor IN ACCESS EXCLUSIVE MODE"), () -> {
            Throwable failure = assertTimeout(
                    Duration.ofSeconds(3), () -> catchThrowable(() -> boundedReads.findProjection(reference)));

            assertSqlState(failure, "57014");
        });

        assertThat(boundedReads.findProjection(reference)).isEmpty();
    }
}
