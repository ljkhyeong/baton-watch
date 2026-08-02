package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable health-change data that may cross the outbound sender boundary. */
public record HealthChangeEventPayload(
        UUID eventId,
        ResourceReference resourceReference,
        SourceRevision sourceRevision,
        Optional<UUID> attemptId,
        Health previousHealth,
        Health currentHealth,
        Instant changedAt) {

    public HealthChangeEventPayload {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(resourceReference, "resourceReference");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(previousHealth, "previousHealth");
        Objects.requireNonNull(currentHealth, "currentHealth");
        Objects.requireNonNull(changedAt, "changedAt");
        if (previousHealth == currentHealth) {
            throw new IllegalArgumentException("health-change event must contain a state change");
        }
    }
}
