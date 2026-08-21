package com.personal.baton.watch.bootstrap;

import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.disabledEventDeliveryProperties;
import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.enabledEventDeliveryProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

class EventDeliveryConfigurationTest {

    @Test
    void enabledDeliveryUsesTheBootManagedJacksonMapperAndCreatesTheSenderGraph() {
        contextRunner(
                        enabledEventDeliveryProperties(),
                        BootstrapTestFixtures.watchProperties(),
                        true)
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ApacheHealthChangeEventSender.class);
            assertThat(context.getBean(HealthChangeEventSender.class))
                    .isInstanceOf(MeteredHealthChangeEventSender.class);
            assertThat(context).hasSingleBean(RunEventDeliveriesUseCase.class);
        });
    }

    @Test
    void enabledDeliveryRejectsReusingTheMonitorApiToken() {
        WatchProperties watchProperties = BootstrapTestFixtures.watchProperties();
        EventDeliveryProperties deliveryProperties =
                enabledEventDeliveryProperties(watchProperties.apiToken());

        contextRunner(deliveryProperties, watchProperties, true).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("event delivery token must differ from the monitor API token");
        });
    }

    @Test
    void disabledDeliveryDoesNotCreateOutboundSenderOrDispatcherBeans() {
        contextRunner(
                        disabledEventDeliveryProperties(),
                        BootstrapTestFixtures.watchProperties(),
                        false)
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ApacheHealthChangeEventSender.class);
            assertThat(context).doesNotHaveBean(HealthChangeEventSender.class);
            assertThat(context).doesNotHaveBean(RunEventDeliveriesUseCase.class);
        });
    }

    private static ApplicationContextRunner contextRunner(
            EventDeliveryProperties deliveryProperties,
            WatchProperties watchProperties,
            boolean enabled) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(EventDeliveryConfiguration.class)
                .withPropertyValues("watch.event-delivery.enabled=" + enabled)
                .withBean(EventDeliveryProperties.class, () -> deliveryProperties)
                .withBean(WatchProperties.class, () -> watchProperties)
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(
                        MonitoringMetrics.class,
                        () -> new MonitoringMetrics(new SimpleMeterRegistry()));
    }
}
