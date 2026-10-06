package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
        TransactionOperations transactions = transactionTemplate();
        JdbcClient jdbcClient = JdbcClient.create(jdbc);
        monitorPersistence = new JdbcMonitorPersistenceAdapter(jdbcClient, transactions);
        checkWorkPersistence = new JdbcCheckWorkPersistenceAdapter(jdbcClient, transactions);
        deliveryPersistence = new JdbcHealthChangeEventDeliveryAdapter(jdbcClient, transactions);
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

    /** 참조에서 만든 대상으로 활성 모니터를 동기화하고 바로 점유한다. */
    protected ClaimedCheck claimed(String reference) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", BASE_TIME);
        return claimOne();
    }

    /** 첫 성공 점검을 완료해 UNKNOWN에서 HEALTHY로 바뀐 미전달 이벤트 하나를 만든다. */
    protected UUID createHealthChangeEvent(String reference) {
        ClaimedCheck check = claimed(reference);
        finalizeAt(check, check.claimedAt());
        return jdbc.queryForObject(
                "SELECT event_id FROM watch_health_change_event WHERE attempt_id = ?",
                UUID.class,
                check.attemptId());
    }

    protected ClaimedHealthChangeEvent claimOneDelivery() {
        return deliveryPersistence.claimPendingEvent(LEASE).orElseThrow();
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
        finalizeAt(claimed, completedAt, CheckObservation.forHttpStatus(200, Duration.ZERO, 0));
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
