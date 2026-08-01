package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WatchPropertiesTest {

    @Test
    void acceptsAConfigurationWhoseLeaseCoversTheBoundedBatch() {
        assertDoesNotThrow(() -> properties(Duration.ofSeconds(30), 2));
    }

    @Test
    void rejectsAConfigurationWhoseChecksCanOutliveTheLease() {
        assertThrows(IllegalArgumentException.class, () -> properties(Duration.ofSeconds(10), 2));
    }

    @Test
    void rejectsShortServiceTokens() {
        WatchProperties.Http http = http();
        assertThrows(IllegalArgumentException.class, () -> new WatchProperties(
                "too-short",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                1,
                100,
                http));
    }

    private static WatchProperties properties(Duration leaseDuration, int batchSize) {
        return new WatchProperties(
                "a-test-token-that-is-longer-than-32-characters",
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                leaseDuration,
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(30),
                batchSize,
                100,
                http());
    }

    private static WatchProperties.Http http() {
        return new WatchProperties.Http(
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
                1);
    }
}
