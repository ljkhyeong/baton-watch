package com.personal.baton.watch.application.monitoring.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CheckFinalization(
        UUID attemptId,
        UUID leaseToken,
        CheckObservation observation,
        Instant completedAt,
        Instant nextCheckAt) {

    public CheckFinalization {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(nextCheckAt, "nextCheckAt");
        if (nextCheckAt.isBefore(completedAt)) {
            throw new IllegalArgumentException("next check cannot precede completion");
        }
    }
}
