package com.personal.baton.watch.domain.monitoring;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MonitorProjection(
        ResourceReference resourceReference,
        SourceRevision sourceRevision,
        MonitoringState monitoringState,
        HealthDerivation derivation,
        Optional<CheckOutcome> lastOutcome,
        Optional<Instant> lastCheckedAt,
        Optional<Instant> nextCheckAt) {

    public MonitorProjection {
        Objects.requireNonNull(resourceReference, "resourceReference");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(monitoringState, "monitoringState");
        Objects.requireNonNull(derivation, "derivation");
        Objects.requireNonNull(lastOutcome, "lastOutcome");
        Objects.requireNonNull(lastCheckedAt, "lastCheckedAt");
        Objects.requireNonNull(nextCheckAt, "nextCheckAt");
        if (monitoringState == MonitoringState.INACTIVE && nextCheckAt.isPresent()) {
            throw new IllegalArgumentException("inactive monitor cannot have a next check time");
        }
    }

    public Health health() {
        return derivation.health();
    }

    public int consecutiveFailures() {
        return derivation.consecutiveFailures();
    }
}
