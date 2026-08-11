package com.personal.baton.watch.adapter.out.external.delivery;

import java.time.Duration;

final class EventDeliveryTestFixtures {

    static final EventDeliveryLimits DEFAULT_LIMITS = new EventDeliveryLimits(
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            8L * 1024L,
            100,
            8 * 1024);

    private EventDeliveryTestFixtures() {
    }
}
