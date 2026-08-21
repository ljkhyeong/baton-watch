package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;

class ApacheHttpLoggingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "logging.level.root=DEBUG",
                    "logging.level.org.apache.hc.client5.http=DEBUG");

    @Test
    void keepsSensitiveApacheHttpDebugDiagnosticsDisabledWhenParentLoggingIsDebug() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RecordingLoggingSystem loggingSystem = new RecordingLoggingSystem();
            new TestLoggingApplicationListener().apply(loggingSystem, context.getEnvironment());

            assertThat(loggingSystem.level("org.apache.hc.client5.http"))
                    .isEqualTo(LogLevel.DEBUG);
            assertThat(loggingSystem.level("org.apache.hc.client5.http.headers"))
                    .isEqualTo(LogLevel.OFF);
            assertThat(loggingSystem.level("org.apache.hc.client5.http.wire"))
                    .isEqualTo(LogLevel.OFF);
            assertThat(loggingSystem.level("org.apache.hc.client5.http.impl"))
                    .isEqualTo(LogLevel.OFF);
            assertThat(loggingSystem.level("org.apache.hc.client5.http.ssl"))
                    .isEqualTo(LogLevel.OFF);
        });
    }

    private static final class TestLoggingApplicationListener extends LoggingApplicationListener {

        void apply(LoggingSystem loggingSystem, ConfigurableEnvironment environment) {
            setLogLevels(loggingSystem, environment);
        }
    }

    private static final class RecordingLoggingSystem extends LoggingSystem {

        private final Map<String, LogLevel> levels = new HashMap<>();

        @Override
        public void beforeInitialize() {}

        @Override
        public void setLogLevel(String loggerName, LogLevel level) {
            levels.put(loggerName == null ? ROOT_LOGGER_NAME : loggerName, level);
        }

        LogLevel level(String loggerName) {
            return levels.get(loggerName);
        }
    }
}
