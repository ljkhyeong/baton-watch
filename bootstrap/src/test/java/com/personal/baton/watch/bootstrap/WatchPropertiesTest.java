package com.personal.baton.watch.bootstrap;

import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.productionProperties;
import static com.personal.baton.watch.bootstrap.BootstrapTestFixtures.watchProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class WatchPropertiesTest {

    private static final String CURRENT_TOKEN = "monitor-api-token-0123456789-abcdef";
    private static final String PREVIOUS_TOKEN = "previous-monitor-api-token-0123456789";

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

    @Test
    void acceptsADistinctPreviousTokenAndTreatsAnEmptyOneAsUnset() {
        assertThat(watchProperties(CURRENT_TOKEN, PREVIOUS_TOKEN).apiPreviousToken()).isEqualTo(PREVIOUS_TOKEN);
        assertThat(watchProperties(CURRENT_TOKEN, "").apiPreviousToken()).isNull();
        assertThat(watchProperties(CURRENT_TOKEN).apiPreviousToken()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "too-short",
        "previous api token 0123456789 abcdef",
        CURRENT_TOKEN
    })
    void rejectsInvalidOrReusedPreviousTokensWithoutExposingThem(String previousToken) {
        BindException failure = assertThrows(
                BindException.class, () -> watchProperties(CURRENT_TOKEN, previousToken));

        assertThat(failure).rootCause().isInstanceOf(IllegalArgumentException.class);
        assertThat(Throwables.getStackTrace(failure)).doesNotContain(previousToken);
    }

    @Test
    void bindsThePreviousTokenFromTheLocalVariableAndTheStagingSecretFile(@TempDir Path secrets)
            throws IOException {
        // 로컬 Compose는 환경 변수, 스테이징은 진입 스크립트가 복사한 configtree 파일로 넘긴다.
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("WATCH_API_TOKEN", CURRENT_TOKEN, "WATCH_API_PREVIOUS_TOKEN", PREVIOUS_TOKEN)));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertThat(Binder.get(environment).bindOrCreate("watch", WatchProperties.class).apiPreviousToken())
                .isEqualTo(PREVIOUS_TOKEN);

        Files.writeString(secrets.resolve("watch.api-previous-token"), PREVIOUS_TOKEN + "\n");
        assertThat(productionProperties("watch", WatchProperties.class, Map.of(
                        "watch.api-token", CURRENT_TOKEN,
                        "spring.config.import", "configtree:" + secrets + "/"))
                .apiPreviousToken()).isEqualTo(PREVIOUS_TOKEN);
    }

    private static void assertRejectedWithoutExposure(String token) {
        BindException failure = assertThrows(BindException.class, () -> watchProperties(token));

        assertThat(failure).rootCause().isInstanceOf(IllegalArgumentException.class);
        assertThat(Throwables.getStackTrace(failure)).doesNotContain(token);
    }
}
