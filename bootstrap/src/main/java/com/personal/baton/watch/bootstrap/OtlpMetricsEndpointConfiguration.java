package com.personal.baton.watch.bootstrap;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(prefix = "management.otlp.metrics.export", name = "enabled")
class OtlpMetricsEndpointConfiguration {

    OtlpMetricsEndpointConfiguration(OtlpMetricsProperties properties) {
        URI endpoint = parse(properties.getUrl());
        if (!endpoint.isAbsolute()
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null) {
            throw invalidEndpoint();
        }
    }

    private static URI parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalidEndpoint();
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw invalidEndpoint();
        }
    }

    private static IllegalArgumentException invalidEndpoint() {
        return new IllegalArgumentException(
                "OTLP 메트릭을 활성화하려면 절대 HTTPS 엔드포인트가 필요합니다");
    }
}
