package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HealthChangeEventRequestTest {

    @Test
    void writesExactlyTheEightAdoptedCallbackFields() {
        HealthChangeEventPayload payload = payload(
                Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                42,
                Instant.parse("2026-08-02T01:02:03.456Z"));

        assertEquals(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"eventType\":\"RESOURCE_HEALTH_CHANGED\","
                        + "\"resourceReference\":\"role-resource-123\","
                        + "\"sourceRevision\":42,"
                        + "\"attemptId\":\"00000000-0000-0000-0000-000000000003\","
                        + "\"previousHealth\":\"DEGRADED\","
                        + "\"currentHealth\":\"BROKEN\","
                        + "\"changedAt\":\"2026-08-02T01:02:03.456Z\"}",
                new String(HealthChangeEventRequest.serialize(payload), StandardCharsets.UTF_8));
    }

    @Test
    void omitsAttemptIdEntirelyWhenTheHealthChangeDidNotComeFromAnAttempt() {
        JsonNode json = JsonMapper.shared().readTree(HealthChangeEventRequest.serialize(
                payload(Optional.empty(), 42, Instant.parse("2026-08-02T01:02:03Z"))));

        assertEquals(7, json.size());
        assertFalse(json.has("attemptId"));
    }

    @Test
    void preservesLongRevisionAndNanosecondUtcTimestampWithoutNumericCoercion() {
        JsonNode json = JsonMapper.shared().readTree(HealthChangeEventRequest.serialize(payload(
                Optional.empty(),
                Long.MAX_VALUE,
                Instant.parse("2026-08-02T01:02:03.123456789Z"))));

        assertEquals(Long.MAX_VALUE, json.required("sourceRevision").longValue());
        assertEquals("2026-08-02T01:02:03.123456789Z", json.required("changedAt").stringValue());
    }

    private static HealthChangeEventPayload payload(
            Optional<UUID> attemptId, long revision, Instant changedAt) {
        return new HealthChangeEventPayload(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ResourceReference("role-resource-123"),
                new SourceRevision(revision),
                attemptId,
                Health.DEGRADED,
                Health.BROKEN,
                changedAt);
    }
}
