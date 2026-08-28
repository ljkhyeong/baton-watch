package com.personal.baton.watch.application.monitoring.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedHealthChangeEvent(
        HealthChangeEventPayload payload,
        UUID leaseToken,
        int deliveryAttempt,
        Instant claimedAt,
        boolean recoveredLease) {

    public ClaimedHealthChangeEvent {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(leaseToken, "leaseToken");
        if (deliveryAttempt <= 0) {
            throw new IllegalArgumentException("delivery attempt must be positive");
        }
        Objects.requireNonNull(claimedAt, "claimedAt");
    }
}
