package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcCheckWorkPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcHealthChangeEventDeliveryAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcMonitorPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.PostgresTransactionOperations;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

class PersistenceAutoWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcTemplateAutoConfiguration.class,
                    JdbcClientAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(
                    PersistenceTransactionConfiguration.class,
                    MonitoringConfiguration.class,
                    EventDeliveryConfiguration.class)
            .withPropertyValues(
                    "watch.event-delivery.enabled=false",
                    "watch.persistence.transaction-timeout=5s",
                    "watch.persistence.lock-timeout=1s",
                    "spring.jdbc.template.query-timeout=3s")
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(WatchProperties.class, BootstrapTestFixtures::watchProperties)
            .withBean(
                    EventDeliveryProperties.class,
                    BootstrapTestFixtures::disabledEventDeliveryProperties);

    @Test
    void bootAutoConfiguresJdbcAndTransactionCollaboratorsForEveryPersistenceAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout()).isEqualTo(3);
            assertThat(context).hasSingleBean(TransactionOperations.class);
            assertThat(context.getBean(TransactionOperations.class))
                    .isInstanceOf(PostgresTransactionOperations.class);

            assertThat(context).hasSingleBean(JdbcMonitorPersistenceAdapter.class);
            assertThat(context).hasSingleBean(MonitorPersistencePort.class);
            assertThat(context).hasSingleBean(JdbcCheckWorkPersistenceAdapter.class);
            assertThat(context).hasSingleBean(CheckWorkPersistencePort.class);
            assertThat(context).hasSingleBean(JdbcHealthChangeEventDeliveryAdapter.class);
            assertThat(context).hasSingleBean(HealthChangeEventDeliveryPersistencePort.class);
        });
    }
}
