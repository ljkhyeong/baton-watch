package com.personal.baton.watch.application.monitoring.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventDeliveryFinalization(
        UUID eventId,
        UUID leaseToken,
        EventDeliveryObservation observation,
        Instant completedAt,
        Instant nextAttemptAt) {

    public EventDeliveryFinalization {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(completedAt, "completedAt");
        if (observation.outcome().isDelivered()) {
            if (nextAttemptAt != null) {
                throw new IllegalArgumentException("delivered event cannot have a next attempt");
            }
        } else {
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
            if (!nextAttemptAt.isAfter(completedAt)) {
                throw new IllegalArgumentException("next attempt must follow completion");
            }
        }
    }
}
