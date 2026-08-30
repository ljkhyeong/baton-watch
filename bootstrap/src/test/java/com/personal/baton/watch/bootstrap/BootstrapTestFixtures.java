package com.personal.baton.watch.bootstrap;

import java.net.URI;
import java.time.Duration;

final class BootstrapTestFixtures {

    private BootstrapTestFixtures() {
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
                        65_536,
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
                URI.create(""),
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
