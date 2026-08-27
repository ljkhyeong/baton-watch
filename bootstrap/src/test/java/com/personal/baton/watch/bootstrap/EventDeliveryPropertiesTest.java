package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventDeliveryPropertiesTest {

    @Test
    void enforcesTheLeaseBudgetAndLetsTheTotalTimeoutCapHttpPhases() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                URI.create("https://baton.example.com/events"),
                "a-separate-delivery-token-longer-than-32-characters",
                Duration.ofSeconds(50),
                10));
        new EventDeliveryProperties.Http(
                Duration.ofSeconds(6),
                Duration.ofSeconds(7),
                Duration.ofSeconds(5),
                8_192,
                100,
                8_192,
                2,
                8,
                1,
                1);
    }

    private static EventDeliveryProperties properties(
            boolean enabled,
            URI endpoint,
            String token,
            Duration leaseDuration,
            int batchSize) {
        return new EventDeliveryProperties(
                enabled,
                endpoint,
                token,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                leaseDuration,
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                batchSize,
                100,
                http());
    }

    private static EventDeliveryProperties.Http http() {
        return new EventDeliveryProperties.Http(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                8_192,
                100,
                8_192,
                2,
                8,
                1,
                1);
    }
}
