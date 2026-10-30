package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

class PostgresTransactionOperationsIntegrationTest
        extends MonitoringPersistenceIntegrationTestSupport {

    private static final Duration TEST_LOCK_TIMEOUT = Duration.ofMillis(250);

    @Test
    void failsFastWhenAnotherTransactionHoldsTheMonitorRowLock() throws Exception {
        String reference = "resource:lock-timeout";
        synchronize(reference, 1, "https://lock-timeout.example/path", BASE_TIME);
        DataSource lockLimited = lockTimeoutDataSource(TEST_LOCK_TIMEOUT);
        JdbcMonitorPersistenceAdapter boundedPersistence = new JdbcMonitorPersistenceAdapter(
                JdbcClient.create(lockLimited),
                boundedTransactions(lockLimited, Duration.ofSeconds(5)));
        SynchronizeMonitorCommand update = SynchronizeMonitorCommand.active(
                new ResourceReference(reference),
                new SourceRevision(2),
                new TargetUrl("https://updated.example/path"));

        withLockHeld(
                () -> lockMonitorRow(reference),
                () -> {
                    Throwable failure = assertTimeout(Duration.ofSeconds(2), () -> catchThrowable(
                            () -> boundedPersistence.synchronize(update, BASE_TIME.plusSeconds(1))));

                    assertSqlState(failure, "55P03");
                    assertThat(projection(reference).sourceRevision().value()).isEqualTo(1);
                });

        assertThat(boundedPersistence.synchronize(update, BASE_TIME.plusSeconds(1))
                .projection()
                .sourceRevision()
                .value())
                .isEqualTo(2);
    }

    @Test
    void checkFinalizationFailsFastOnTheMonitorRowLockAndKeepsTheClaim() throws Exception {
        String reference = "resource:finalize-lock";
        synchronize(reference, 1, "https://finalize-lock.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne();
        DataSource lockLimited = lockTimeoutDataSource(TEST_LOCK_TIMEOUT);
        JdbcCheckWorkPersistenceAdapter boundedPersistence = new JdbcCheckWorkPersistenceAdapter(
                JdbcClient.create(lockLimited),
                boundedTransactions(lockLimited, Duration.ofSeconds(5)));
        CheckFinalization done = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0),
                claimed.claimedAt().plusSeconds(1),
                claimed.claimedAt().plus(INTERVAL).plusSeconds(1));

        withLockHeld(
                () -> lockMonitorRow(reference),
                () -> {
                    Throwable failure = assertTimeout(Duration.ofSeconds(2), () -> catchThrowable(
                            () -> boundedPersistence.finalizeCheck(done)));

                    assertSqlState(failure, "55P03");
                });

        assertThat(countRowsInTable(jdbc, "watch_result")).isZero();
        assertThat(countRowsInTable(jdbc, "watch_health_change_event")).isZero();
        assertThat(checkWorkPersistence.claimDueCheck(LEASE)).isEmpty();
        assertThat(boundedPersistence.finalizeCheck(done)).isEqualTo(CheckFinalizationStatus.APPLIED);
    }

    @Test
    void rollsBackAllWritesWhenTheTotalTransactionTimeoutExpires() {
        String reference = "resource:transaction-timeout";
        TransactionOperations transactions = boundedTransactions(testDataSource, Duration.ofSeconds(1));

        Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO watch_monitor (
                        resource_reference,
                        source_revision,
                        monitor_status,
                        target_url,
                        current_health,
                        consecutive_failures,
                        next_check_at
                    ) VALUES (?, 0, 'INACTIVE', NULL, 'UNKNOWN', 0, NULL)
                    """, reference);
            jdbc.execute("SELECT pg_sleep(3)");
        }));

        assertSqlState(failure, "57014");
        assertThat(countRowsInTable(jdbc, "watch_monitor")).isZero();
    }

    @Test
    void rejectsAnOuterTransactionBeforePersistenceWorkStarts() {
        TransactionOperations transactions = boundedTransactions(testDataSource, Duration.ofSeconds(5));
        TransactionTemplate outer = transactionTemplate();
        AtomicBoolean persistenceWorkStarted = new AtomicBoolean();

        Throwable failure = catchThrowable(() -> outer.executeWithoutResult(status ->
                transactions.executeWithoutResult(innerStatus -> persistenceWorkStarted.set(true))));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not join an existing transaction");
        assertThat(persistenceWorkStarted).isFalse();
    }

    private void lockMonitorRow(String reference) {
        jdbc.queryForObject(
                "SELECT resource_reference FROM watch_monitor WHERE resource_reference = ? FOR UPDATE",
                String.class,
                reference);
    }

    private static TransactionOperations boundedTransactions(
            DataSource dataSource, Duration transactionTimeout) {
        TransactionTemplate delegate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        delegate.setTimeout(Math.toIntExact(transactionTimeout.toSeconds()));
        return new PostgresTransactionOperations(delegate);
    }
}
