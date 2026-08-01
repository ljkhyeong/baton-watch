package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CheckFinalization(
        UUID attemptId,
        UUID leaseToken,
        ResourceReference resourceReference,
        SourceRevision sourceRevision,
        CheckObservation observation,
        Instant completedAt,
        Instant nextCheckAt) {

    public CheckFinalization {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(resourceReference, "resourceReference");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(nextCheckAt, "nextCheckAt");
        if (nextCheckAt.isBefore(completedAt)) {
            throw new IllegalArgumentException("next check cannot precede completion");
        }
    }
}
