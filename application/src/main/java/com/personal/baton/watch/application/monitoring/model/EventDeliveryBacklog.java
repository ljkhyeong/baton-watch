package com.personal.baton.watch.application.monitoring.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record EventDeliveryBacklog(long pendingCount, Optional<Duration> oldestEventAge) {

    public EventDeliveryBacklog {
        if (pendingCount < 0) {
            throw new IllegalArgumentException("pending count must be non-negative");
        }
        Objects.requireNonNull(oldestEventAge, "oldestEventAge");
        if ((pendingCount == 0) != oldestEventAge.isEmpty()) {
            throw new IllegalArgumentException("oldest event age must match pending count");
        }
        if (oldestEventAge.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("oldest event age must be non-negative");
        }
    }
}
