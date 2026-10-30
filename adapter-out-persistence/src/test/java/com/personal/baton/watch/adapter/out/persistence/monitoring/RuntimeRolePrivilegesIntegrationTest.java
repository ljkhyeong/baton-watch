package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult.Status;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 다른 영속성 시험은 소유자로 접속하므로, 배포 권한 파일을 적용한 런타임 역할로 어댑터 경로를 실행한다. */
class RuntimeRolePrivilegesIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    private static final String RUNTIME_ROLE = "baton_watch_runtime_test";
    private static final String RUNTIME_PASSWORD = "integration-test-runtime";

    @BeforeEach
    void connectAdaptersAsTheRuntimeRole() {
        jdbc.execute("""
                DO $watch$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                        CREATE ROLE %1$s LOGIN PASSWORD '%2$s';
                    END IF;
                END
                $watch$
                """.formatted(RUNTIME_ROLE, RUNTIME_PASSWORD));
        Flyway.configure()
                .dataSource(testDataSource)
                .locations("classpath:db/migration", "filesystem:../ops/flyway")
                .placeholders(Map.of("runtimeRole", RUNTIME_ROLE))
                .load()
                .migrate();

        DataSource runtime = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
        JdbcClient client = JdbcClient.create(runtime);
        TransactionOperations transactions = new TransactionTemplate(new DataSourceTransactionManager(runtime));
        monitorPersistence = new JdbcMonitorPersistenceAdapter(client, transactions);
        checkWorkPersistence = new JdbcCheckWorkPersistenceAdapter(client, transactions);
        deliveryPersistence = new JdbcHealthChangeEventDeliveryAdapter(client, transactions);
    }

    @Test
    void runsEveryAdapterOperationWithTheDeployedPrivileges() {
        Instant cutoff = databaseClock().plus(Duration.ofDays(1));
        ClaimedCheck completed = claimed("resource:completed");
        ClaimedCheck abandoned = claimed("resource:abandoned");
        synchronizeInactive("resource:abandoned", 2, abandoned.claimedAt());
        finalizeAt(completed, completed.claimedAt());

        var event = claimOneDelivery();
        assertThat(deliveryPersistence.finalizeDelivery(new EventDeliveryFinalization(
                        event.payload().eventId(),
                        event.leaseToken(),
                        EventDeliveryObservation.forHttpStatus(204, null),
                        event.claimedAt(),
                        null)))
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        assertThat(deliveryPersistence.getBacklogSnapshot().pendingCount()).isZero();
        assertThat(checkWorkPersistence.getOldestDueCheckDelay().isNegative()).isFalse();
        assertThat(monitorPersistence.requestCheck(
                        new ResourceReference("resource:completed"), completed.claimedAt(), Duration.ofSeconds(30))
                .status()).isEqualTo(Status.SCHEDULED);

        assertThat(checkWorkPersistence.purgeAttempts(cutoff, 10)).isEqualTo(2);
        assertThat(deliveryPersistence.purgeDeliveredEvents(cutoff, 10)).isOne();
        assertThat(monitorPersistence.markStaleUnknown(cutoff, cutoff, 10)).isOne();
    }
}
