package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcCheckWorkPersistenceAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcHealthChangeEventDeliveryAdapter;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcMonitorPersistenceAdapter;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

class PersistenceAutoWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcTemplateAutoConfiguration.class,
                    JdbcClientAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(MonitoringConfiguration.class, EventDeliveryConfiguration.class)
            .withPropertyValues("watch.event-delivery.enabled=false")
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
            assertThat(context).hasSingleBean(JdbcTemplate.class);
            assertThat(context).hasSingleBean(JdbcClient.class);
            assertThat(context).hasSingleBean(PlatformTransactionManager.class);
            assertThat(context).hasSingleBean(TransactionOperations.class);

            assertThat(context).hasSingleBean(JdbcMonitorPersistenceAdapter.class);
            assertThat(context).hasSingleBean(MonitorPersistencePort.class);
            assertThat(context).hasSingleBean(JdbcCheckWorkPersistenceAdapter.class);
            assertThat(context).hasSingleBean(CheckWorkPersistencePort.class);
            assertThat(context).hasSingleBean(JdbcHealthChangeEventDeliveryAdapter.class);
            assertThat(context).hasSingleBean(HealthChangeEventDeliveryPersistencePort.class);

            assertThat(context).doesNotHaveBean(ApacheHealthChangeEventSender.class);
            assertThat(context).doesNotHaveBean(HealthChangeEventSender.class);
            assertThat(context).doesNotHaveBean(RunEventDeliveriesUseCase.class);
        });
    }
}
