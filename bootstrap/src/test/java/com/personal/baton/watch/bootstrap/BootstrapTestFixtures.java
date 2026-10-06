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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    static WatchProperties watchProperties() {
        return watchProperties("a-test-token-that-is-longer-than-32-characters");
    }

    static WatchProperties watchProperties(String apiToken) {
        return new WatchProperties(
                apiToken,
                true,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                1,
                100,
                new WatchProperties.Http(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        3,
                        100,
                        8_192,
                        2,
                        8,
                        1,
                        1));
    }

    static DatabaseRuntimeProperties databaseRuntimeProperties() {
        return new DatabaseRuntimeProperties(
                4,
                1,
                3_000,
                1_000,
                600_000,
                1_800_000,
                300_000,
                1_000,
                3,
                3,
                10,
                3,
                true);
    }

    static PersistenceProperties persistenceProperties() {
        return new PersistenceProperties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1));
    }

    static EventDeliveryProperties disabledEventDeliveryProperties() {
        return new EventDeliveryProperties(
                false,
                "",
                "",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                2,
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
}
