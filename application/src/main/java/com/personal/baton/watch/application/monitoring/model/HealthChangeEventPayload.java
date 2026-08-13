package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 송신자 경계를 통과할 수 있는 불변 상태 변경 데이터다. */
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
