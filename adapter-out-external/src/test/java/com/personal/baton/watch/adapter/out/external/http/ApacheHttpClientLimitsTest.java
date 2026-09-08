package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ApacheHttpClientLimitsTest {

    @Test
    void capsConnectAndResponseTimeoutsAtRemainingTime() {
        ApacheHttpClientLimits limits = ApacheHttpClientLimits.cappedBy(
                Duration.ofSeconds(6),
                Duration.ofSeconds(7),
                Duration.ofSeconds(5),
                100,
                8_192);

        assertEquals(Duration.ofSeconds(5), limits.connectTimeout());
        assertEquals(Duration.ofSeconds(5), limits.responseTimeout());
    }

    @Test
    void preservesShorterPhaseTimeouts() {
        ApacheHttpClientLimits limits = ApacheHttpClientLimits.cappedBy(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                100,
                8_192);

        assertEquals(Duration.ofSeconds(2), limits.connectTimeout());
        assertEquals(Duration.ofSeconds(3), limits.responseTimeout());
    }
}
