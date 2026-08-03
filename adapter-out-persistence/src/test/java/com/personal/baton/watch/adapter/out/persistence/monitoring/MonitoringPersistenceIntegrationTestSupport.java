package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

abstract class MonitoringPersistenceIntegrationTestSupport
        extends PostgresPersistenceIntegrationTestSupport {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration INTERVAL = Duration.ofSeconds(60);
    protected static final long CONCURRENCY_TIMEOUT_SECONDS = 10;

    protected JdbcMonitorPersistenceAdapter monitorPersistence;
    protected JdbcCheckWorkPersistenceAdapter checkWorkPersistence;

    @BeforeEach
    void initializeMonitoringPersistenceAdapters() {
        TransactionOperations transactions = newTransactionOperations();
        monitorPersistence = new JdbcMonitorPersistenceAdapter(jdbc, transactions);
        checkWorkPersistence = new JdbcCheckWorkPersistenceAdapter(jdbc, transactions);
    }

    protected JdbcCheckWorkPersistenceAdapter newCheckWorkPersistenceAdapter() {
        return new JdbcCheckWorkPersistenceAdapter(
                new JdbcTemplate(testDataSource), newTransactionOperations());
    }

    protected TransactionOperations newTransactionOperations() {
        return new TransactionTemplate(new DataSourceTransactionManager(testDataSource));
    }

    protected SynchronizationResult synchronize(
            String reference, long revision, String target, Instant at) {
        return monitorPersistence.synchronize(
                SynchronizeMonitorCommand.active(
                        new ResourceReference(reference),
                        new SourceRevision(revision),
                        new TargetUrl(target)),
                at);
    }

    protected ClaimedCheck claimOne(Instant claimedAt) {
        List<ClaimedCheck> claims = checkWorkPersistence.claimDueChecks(
                claimedAt, claimedAt.plus(LEASE), 1);
        assertThat(claims).hasSize(1);
        return claims.getFirst();
    }

    protected CheckFinalization finalization(
            ClaimedCheck claimed,
            CheckObservation observation,
            Instant completedAt,
            Instant nextCheckAt) {
        return new CheckFinalization(
                claimed.attemptId(),
                claimed.leaseToken(),
                claimed.resourceReference(),
                claimed.sourceRevision(),
                observation,
                completedAt,
                nextCheckAt);
    }

    protected MonitorProjection projection(String reference) {
        return monitorPersistence.findProjection(new ResourceReference(reference)).orElseThrow();
    }

    protected int count(String table) {
        if (!List.of("watch_attempt", "watch_result", "watch_health_change_event").contains(table)) {
            throw new IllegalArgumentException("unsupported test table");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    protected static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    protected static void shutdownAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(
                CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
}
