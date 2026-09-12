package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(OutputCaptureExtension.class)
class EventDeliveryConfigurationTest {

    private static final String API_TOKEN = "a-test-token-that-is-longer-than-32-characters";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(Settings.class, EventDeliveryConfiguration.class,
                    EventDeliveryScheduler.class, EventDeliveryMaintenanceScheduler.class)
            .withPropertyValues(
                    "watch.api-token=" + API_TOKEN,
                    "watch.event-delivery.endpoint=https://baton.example.com/callback",
                    "watch.event-delivery.bearer-token=a-separate-delivery-token-longer-than-32-characters")
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC))
            .withBean(MonitoringMetrics.class, () -> new MonitoringMetrics(new SimpleMeterRegistry()));

    @ParameterizedTest(name = "전달 설정 {0}의 바인딩과 전체 전달 빈 등록 일치")
    @CsvSource({
            "default,false", "true,true", "false,false", "on,true", "yes,true", "1,true",
            "' true ',true", "off,false", "no,false", "0,false", "' false ',false"
    })
    void createsDeliveryBeansAccordingToBoundSetting(String enabled, boolean expected) {
        ApplicationContextRunner configured = withDeliverySetting(enabled);
        if (!expected) {
            configured = configured.withPropertyValues(
                    "watch.event-delivery.endpoint=", "watch.event-delivery.bearer-token=");
        }
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EventDeliveryProperties.class).enabled()).isEqualTo(expected);
            assertThat(context).hasSingleBean(EventDeliveryMaintenanceScheduler.class);
            if (expected) {
                assertThat(context).hasSingleBean(ApacheHealthChangeEventSender.class)
                        .hasSingleBean(RunEventDeliveriesUseCase.class)
                        .hasSingleBean(EventDeliveryScheduler.class);
                assertThat(context.getBean(HealthChangeEventSender.class))
                        .isInstanceOf(MeteredHealthChangeEventSender.class);
            } else {
                assertThat(context).doesNotHaveBean(HealthChangeEventSender.class)
                        .doesNotHaveBean(RunEventDeliveriesUseCase.class)
                        .doesNotHaveBean(EventDeliveryScheduler.class);
            }
        });
    }

    @ParameterizedTest(name = "전달 설정 {0}에서도 API 토큰 재사용 거부")
    @ValueSource(strings = {"true", "on"})
    void enabledDeliveryRejectsReusingTheMonitorApiToken(String enabled) {
        withDeliverySetting(enabled).withPropertyValues("watch.event-delivery.bearer-token=" + API_TOKEN)
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("event delivery token must differ from the monitor API token");
                });
    }

    @Test
    void rejectsInvalidSettingInsteadOfSilentlyDisablingDelivery() {
        withDeliverySetting("enabled").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://baton.example/callback/a%2Fb",
        "https://baton.example/callback/a%20b"
    })
    void preservesConfiguredEndpointEncoding(String endpoint) {
        withDeliverySetting("true").withPropertyValues("watch.event-delivery.endpoint=" + endpoint)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EventDeliveryProperties.class).endpointUri().toString())
                            .isEqualTo(endpoint);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://endpoint-sensitive-marker.example/callback bad",
        "https://[endpoint-sensitive-marker/callback",
        "https://user:endpoint-sensitive-marker@baton.example/callback"
    })
    void rejectsInvalidEndpointsWithoutLoggingTheirValues(String endpoint, CapturedOutput output) {
        SpringApplication application = new SpringApplication(
                Settings.class, EventDeliveryConfiguration.class, JacksonAutoConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> {
            GenericApplicationContext beans = (GenericApplicationContext) context;
            beans.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
            beans.registerBean(TransactionOperations.class, () -> mock(TransactionOperations.class));
            beans.registerBean(Clock.class, () -> Clock.fixed(
                    Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
            beans.registerBean(MonitoringMetrics.class, () -> new MonitoringMetrics(new SimpleMeterRegistry()));
        });

        RuntimeException failure = assertThrows(RuntimeException.class, () -> application.run(
                    "--watch.api-token=" + API_TOKEN,
                    "--watch.event-delivery.enabled=true",
                    "--watch.event-delivery.endpoint=" + endpoint,
                    "--watch.event-delivery.bearer-token=a-separate-delivery-token-longer-than-32-characters").close());
        assertThat(failure).hasStackTraceContaining("endpoint");
        assertThat(output.getAll()).doesNotContain("endpoint-sensitive-marker");
    }

    private ApplicationContextRunner withDeliverySetting(String enabled) {
        return enabled.equals("default") ? runner
                : runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource(
                                "deliverySetting", Map.of("watch.event-delivery.enabled", enabled))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({EventDeliveryProperties.class, WatchProperties.class,
            DatabaseRuntimeProperties.class, PersistenceProperties.class})
    static class Settings {
    }
}
