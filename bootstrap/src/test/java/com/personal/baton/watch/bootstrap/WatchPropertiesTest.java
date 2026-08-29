package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WatchPropertiesTest {

    @Test
    void letsTheTotalTimeoutCapHttpPhases() {
        new WatchProperties.Http(
                Duration.ofSeconds(6),
                Duration.ofSeconds(7),
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

    @Test
    void acceptsAnRfc6750Token68ServiceToken() {
        properties("01234567890123456789012345678901==");
        properties("a".repeat(WatchProperties.MAX_API_TOKEN_LENGTH));
    }

    @Test
    void rejectsServiceTokensAboveTheHeaderSafeLimitWithoutExposingThem() {
        String token = "a".repeat(WatchProperties.MAX_API_TOKEN_LENGTH + 1);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> properties(token));

        assertFalse(failure.getMessage().contains(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "too-short",
        "0123456789012345678901234567890",
        "monitor:api:token:0123456789:abcdef",
        "monitor api token 0123456789 abcdef",
        "éééééééééééééééééééééééééééééééé",
        "01234567890123456789012345678901=middle"
    })
    void rejectsNonToken68ServiceTokensWithoutExposingThem(String token) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> properties(token));

        assertFalse(failure.getMessage().contains(token));
    }

    private static WatchProperties properties(String apiToken) {
        return new WatchProperties(
                apiToken,
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
