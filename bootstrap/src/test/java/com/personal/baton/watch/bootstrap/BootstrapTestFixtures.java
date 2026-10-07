package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

final class BootstrapTestFixtures {

    private static final Instant CLAIMED_AT = Instant.parse("2026-08-01T00:00:00Z");

    private BootstrapTestFixtures() {
    }

    static PostgreSQLContainer postgres(String password) {
        return new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
                        System.getProperty("watch.test.postgres-image"),
                        "Gradle 테스트 태스크로 실행해야 합니다: watch.test.postgres-image 없음"))
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("baton_watch")
                .withUsername("baton_watch")
                .withPassword(password);
    }

    static double count(MeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    static Timer timer(MeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).timer();
    }

    static ClaimedCheck claimedCheck(boolean recoveredLease) {
        return new ClaimedCheck(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new TargetUrl("https://example.com/health"),
                CLAIMED_AT,
                recoveredLease);
    }

    static ClaimedHealthChangeEvent claimedEvent(boolean recoveredLease) {
        return new ClaimedHealthChangeEvent(
                new HealthChangeEventPayload(
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        new ResourceReference("resource-1"),
                        new SourceRevision(1),
                        Optional.empty(),
                        Health.UNKNOWN,
                        Health.HEALTHY,
                        CLAIMED_AT),
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                1,
                CLAIMED_AT,
                recoveredLease);
    }

    static WatchProperties watchProperties(String apiToken) {
        return productionProperties("watch", WatchProperties.class, Map.of("watch.api-token", apiToken));
    }

    static DatabaseRuntimeProperties databaseRuntimeProperties() {
        return productionProperties("watch.database", DatabaseRuntimeProperties.class, Map.of());
    }

    static PersistenceProperties persistenceProperties() {
        return productionProperties("watch.persistence", PersistenceProperties.class, Map.of());
    }

    /** 운영 application.yml 기본값을 시스템 환경 변수 없이 바인딩한다. */
    static <T> T productionProperties(String prefix, Class<T> type, Map<String, String> overrides) {
        MockEnvironment environment = new MockEnvironment();
        overrides.forEach(environment::setProperty);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return Binder.get(environment).bindOrCreate(prefix, type);
    }
}
