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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

abstract class MonitoringPersistenceIntegrationTestSupport
        extends PostgresPersistenceIntegrationTestSupport {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration INTERVAL = Duration.ofSeconds(60);

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

    protected ClaimedCheck claimOne() {
        List<ClaimedCheck> claims = checkWorkPersistence.claimDueChecks(LEASE, 1);
        assertThat(claims).hasSize(1);
        return claims.getFirst();
    }

    protected Instant claimedAt(ClaimedCheck claimed) {
        return jdbc.queryForObject(
                "SELECT claimed_at FROM watch_attempt WHERE attempt_id = ?",
                java.time.OffsetDateTime.class,
                claimed.attemptId()).toInstant();
    }

    protected CheckFinalization finalization(
            ClaimedCheck claimed,
            CheckObservation observation,
            Instant completedAt,
            Instant nextCheckAt) {
        return new CheckFinalization(
                claimed.attemptId(),
                claimed.leaseToken(),
                observation,
                completedAt,
                nextCheckAt);
    }

    protected MonitorProjection projection(String reference) {
        return monitorPersistence.findProjection(new ResourceReference(reference)).orElseThrow();
    }

    protected static void assertSqlState(Throwable failure, String expectedState) {
        assertThat(failure).rootCause()
                .isInstanceOfSatisfying(SQLException.class, sqlFailure ->
                        assertThat(sqlFailure.getSQLState()).isEqualTo(expectedState));
    }
}
