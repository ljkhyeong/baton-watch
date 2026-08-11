package com.personal.baton.watch.adapter.out.external.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "eventId",
    "eventType",
    "resourceReference",
    "sourceRevision",
    "attemptId",
    "previousHealth",
    "currentHealth",
    "changedAt"
})
record HealthChangeEventRequest(
        String eventId,
        String eventType,
        String resourceReference,
        long sourceRevision,
        String attemptId,
        String previousHealth,
        String currentHealth,
        String changedAt) {

    private static final String EVENT_TYPE = "RESOURCE_HEALTH_CHANGED";

    static HealthChangeEventRequest from(HealthChangeEventPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return new HealthChangeEventRequest(
                payload.eventId().toString(),
                EVENT_TYPE,
                payload.resourceReference().value(),
                payload.sourceRevision().value(),
                payload.attemptId().map(UUID::toString).orElse(null),
                payload.previousHealth().name(),
                payload.currentHealth().name(),
                payload.changedAt().toString());
    }
}
