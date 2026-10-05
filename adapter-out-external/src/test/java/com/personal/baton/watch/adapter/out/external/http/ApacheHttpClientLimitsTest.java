package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApacheHttpClientLimitsTest {

    @ParameterizedTest
    @CsvSource({
        // 연결 제한, 응답 제한, 남은 시간, 기대 연결 제한, 기대 응답 제한(초)
        "6, 7, 5, 5, 5",
        "2, 3, 5, 2, 3"
    })
    void capsPhaseTimeoutsAtRemainingTimeAndKeepsShorterOnes(
            long connect, long response, long remaining, long expectedConnect, long expectedResponse) {
        ApacheHttpClientLimits limits = new ApacheHttpClientLimits(
                Duration.ofSeconds(connect), Duration.ofSeconds(response), 100, 8_192)
                .cappedBy(Duration.ofSeconds(remaining));

        assertEquals(Duration.ofSeconds(expectedConnect), limits.connectTimeout());
        assertEquals(Duration.ofSeconds(expectedResponse), limits.responseTimeout());
    }
}
