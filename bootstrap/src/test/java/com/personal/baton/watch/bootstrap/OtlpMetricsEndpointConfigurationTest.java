package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class OtlpMetricsEndpointConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("management.otlp.metrics.export.enabled=true");

    @Test
    void acceptsAnAbsoluteHttpsEndpoint() {
        contextRunner
                .withPropertyValues(
                        "management.otlp.metrics.export.url=https://otlp.example.test/v1/metrics")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "http://otlp.example.test/v1/metrics",
        "/v1/metrics",
        "https:opaque"
    })
    void rejectsMissingOrNonHttpsEndpoints(String endpoint) {
        contextRunner
                .withPropertyValues("management.otlp.metrics.export.url=" + endpoint)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage(
                                "OTLP 메트릭을 활성화하려면 절대 HTTPS 엔드포인트가 필요합니다"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OtlpMetricsProperties.class)
    @Import(OtlpMetricsEndpointConfiguration.class)
    static class TestConfiguration {
    }
}
