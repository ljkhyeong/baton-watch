package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EventDeliveryConfigurationTest {

    @Test
    void enabledDeliveryUsesTheBootManagedJacksonMapperAndCreatesTheSenderGraph() {
        contextRunner(enabledProperties(), true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context.getBean(ObjectMapper.class)).isInstanceOf(JsonMapper.class);
            assertThat(context).hasSingleBean(ApacheHealthChangeEventSender.class);
            assertThat(context.getBean(HealthChangeEventSender.class))
                    .isInstanceOf(MeteredHealthChangeEventSender.class);
            assertThat(context).hasSingleBean(RunEventDeliveriesUseCase.class);
        });
    }

    @Test
    void disabledDeliveryDoesNotCreateOutboundSenderOrDispatcherBeans() {
        contextRunner(disabledProperties(), false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ApacheHealthChangeEventSender.class);
            assertThat(context).doesNotHaveBean(HealthChangeEventSender.class);
            assertThat(context).doesNotHaveBean(RunEventDeliveriesUseCase.class);
        });
    }

    private static ApplicationContextRunner contextRunner(
            EventDeliveryProperties deliveryProperties, boolean enabled) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(EventDeliveryConfiguration.class)
                .withPropertyValues("watch.event-delivery.enabled=" + enabled)
                .withBean(EventDeliveryProperties.class, () -> deliveryProperties)
                .withBean(WatchProperties.class, EventDeliveryConfigurationTest::watchProperties)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(
                        MonitoringMetrics.class,
                        () -> new MonitoringMetrics(new SimpleMeterRegistry()));
    }

    private static EventDeliveryProperties enabledProperties() {
        return deliveryProperties(
                true,
                URI.create("https://baton.example.com/api/v1/internal/resource-health-events"),
                "a-separate-delivery-token-longer-than-32-characters");
    }

    private static EventDeliveryProperties disabledProperties() {
        return deliveryProperties(false, URI.create(""), "");
    }

    private static EventDeliveryProperties deliveryProperties(
            boolean enabled, URI endpoint, String token) {
        return new EventDeliveryProperties(
                enabled,
                endpoint,
                token,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                10,
                100,
                new EventDeliveryProperties.Http(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        8_192,
                        100,
                        8_192,
                        2,
                        8,
                        1,
                        1));
    }

    private static WatchProperties watchProperties() {
        return new WatchProperties(
                "a-distinct-monitor-api-token-longer-than-32-characters",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(60),
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                1,
                100,
                new WatchProperties.Http(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        8_192,
                        3,
                        100,
                        8_192,
                        2,
                        8,
                        1,
                        1));
    }
}
