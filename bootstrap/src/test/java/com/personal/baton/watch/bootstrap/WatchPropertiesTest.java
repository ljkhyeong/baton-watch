package com.personal.baton.watch.bootstrap;

import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.watchProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;

class WatchPropertiesTest {

    @Test
    void acceptsAnRfc6750Token68ServiceToken() {
        watchProperties("01234567890123456789012345678901==");
        watchProperties("a".repeat(WatchProperties.MAX_API_TOKEN_LENGTH));
    }

    @Test
    void rejectsServiceTokensAboveTheHeaderSafeLimitWithoutExposingThem() {
        assertRejectedWithoutExposure("a".repeat(WatchProperties.MAX_API_TOKEN_LENGTH + 1));
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
        assertRejectedWithoutExposure(token);
    }

    private static void assertRejectedWithoutExposure(String token) {
        BindException failure = assertThrows(BindException.class, () -> watchProperties(token));

        assertThat(failure).rootCause().isInstanceOf(IllegalArgumentException.class);
        assertThat(Throwables.getStackTrace(failure)).doesNotContain(token);
    }
}
