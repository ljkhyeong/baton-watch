package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RuntimeSafetyEnvironmentPostProcessorTest {

    @Test
    void keepsFixedSafetyValuesAheadOfExternalConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "externalOverrides",
                Map.ofEntries(
                        Map.entry("spring.task.scheduling.shutdown.await-termination", "false"),
                        Map.entry("server.tomcat.max-connections", "4096"),
                        Map.entry("logging.level.org.apache.hc.client5.http.headers", "DEBUG"),
                        Map.entry("management.endpoint.health.show-details", "always"),
                        Map.entry("management.endpoints.web.exposure.include", "*"))));

        new RuntimeSafetyEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(RuntimeSafetyEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination"))
                .isEqualTo("true");
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
                .isEqualTo("65s");
        assertThat(environment.getProperty("server.tomcat.max-connections"))
                .isEqualTo("128");
        assertThat(environment.getProperty("logging.level.org.apache.hc.client5.http.headers"))
                .isEqualTo("OFF");
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
    }
}
