package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** BATON에 전달할 상태 변경 데이터다. 생성 후에는 변경하지 않는다. */
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
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(previousHealth, "previousHealth");
        Objects.requireNonNull(currentHealth, "currentHealth");
        Objects.requireNonNull(changedAt, "changedAt");
        if (previousHealth == currentHealth) {
            throw new IllegalArgumentException("health-change event must contain a state change");
        }
    }
}
