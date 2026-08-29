package com.personal.baton.watch.application.monitoring.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record EventDeliveryBacklogSnapshot(long pendingCount, Optional<Instant> oldestChangedAt) {

    public EventDeliveryBacklogSnapshot {
        if (pendingCount < 0) {
            throw new IllegalArgumentException("pending count must be non-negative");
        }
        Objects.requireNonNull(oldestChangedAt, "oldestChangedAt");
        if ((pendingCount == 0) != oldestChangedAt.isEmpty()) {
            throw new IllegalArgumentException("oldest changed time must match pending count");
        }
    }
}
