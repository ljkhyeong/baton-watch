package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

abstract class MonitoringPersistenceIntegrationTestSupport
        extends PostgresPersistenceIntegrationTestSupport {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration INTERVAL = Duration.ofSeconds(60);

    protected JdbcMonitorPersistenceAdapter monitorPersistence;
    protected JdbcCheckWorkPersistenceAdapter checkWorkPersistence;
    protected JdbcHealthChangeEventDeliveryAdapter deliveryPersistence;

    @BeforeEach
    void initializeMonitoringPersistenceAdapters() {
        TransactionOperations transactions = newTransactionOperations();
        JdbcClient jdbcClient = JdbcClient.create(jdbc);
        monitorPersistence = new JdbcMonitorPersistenceAdapter(jdbcClient, transactions);
        checkWorkPersistence = new JdbcCheckWorkPersistenceAdapter(jdbcClient, transactions);
        deliveryPersistence = new JdbcHealthChangeEventDeliveryAdapter(jdbcClient, transactions);
    }

    protected JdbcCheckWorkPersistenceAdapter newCheckWorkPersistenceAdapter() {
        return new JdbcCheckWorkPersistenceAdapter(
                JdbcClient.create(new JdbcTemplate(testDataSource)), newTransactionOperations());
    }

    protected TransactionOperations newTransactionOperations() {
        return transactionTemplate();
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

    protected SynchronizationResult synchronizeInactive(String reference, long revision, Instant at) {
        return monitorPersistence.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference(reference), new SourceRevision(revision)),
                at);
    }

    protected ClaimedCheck claimOne() {
        return checkWorkPersistence.claimDueCheck(LEASE).orElseThrow();
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

    protected void finalizeAt(ClaimedCheck claimed, Instant completedAt) {
        finalizeAt(claimed, completedAt, CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0));
    }

    protected void finalizeAt(ClaimedCheck claimed, Instant completedAt, CheckObservation observation) {
        assertThat(checkWorkPersistence.finalizeCheck(finalization(
                        claimed, observation, completedAt, completedAt.plus(INTERVAL))))
                .isEqualTo(CheckFinalizationStatus.APPLIED);
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
