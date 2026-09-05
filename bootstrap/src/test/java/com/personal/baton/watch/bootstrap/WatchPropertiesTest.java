package com.personal.baton.watch.bootstrap;

import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.watchProperties;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WatchPropertiesTest {

    @Test
    void acceptsAnRfc6750Token68ServiceToken() {
        watchProperties("01234567890123456789012345678901==");
        watchProperties("a".repeat(WatchProperties.MAX_API_TOKEN_LENGTH));
    }

    @Test
    void rejectsServiceTokensAboveTheHeaderSafeLimitWithoutExposingThem() {
        String token = "a".repeat(WatchProperties.MAX_API_TOKEN_LENGTH + 1);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> watchProperties(token));

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
                () -> watchProperties(token));

        assertFalse(failure.getMessage().contains(token));
    }

}
