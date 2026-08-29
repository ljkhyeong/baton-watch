package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MetricsRegistryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    OtlpMetricsExportAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class))
            .withUserConfiguration(OtlpMetricsEndpointConfiguration.class)
            .withBean(OtlpMetricsSender.class, () -> request -> {});

    @Test
    void keepsPrometheusEnabledAndOtlpDisabledByDefault() {
        contextRunner
                .withPropertyValues("management.otlp.metrics.export.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(PrometheusMeterRegistry.class);
                    assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
                });
    }

    @Test
    void addsOtlpWithoutReplacingPrometheusWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "management.otlp.metrics.export.enabled=true",
                        "management.otlp.metrics.export.url=https://otlp.example.test/v1/metrics",
                        "management.otlp.metrics.export.step=1d")
                .run(context -> {
                    assertThat(context).hasSingleBean(PrometheusMeterRegistry.class);
                    assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
                });
    }
}
