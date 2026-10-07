package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PinnedApacheClientFactoryTest {

    @ParameterizedTest
    @CsvSource({
        // 단계 제한, 남은 시간, 기대 소켓 제한(나노초)
        "6000000000, 5000000000, 5000000000",
        "2000000000, 5000000000, 2000000000",
        "2000000000, 999999, 1000000",
        "1, 5000000000, 1000000"
    })
    void capsPhaseTimeoutsAtRemainingTimeWithAOneMillisecondFloor(long limit, long remaining, long expected) {
        assertEquals(
                Duration.ofNanos(expected),
                PinnedApacheClientFactory.socketTimeout(Duration.ofNanos(limit), Duration.ofNanos(remaining))
                        .toDuration());
    }
}
