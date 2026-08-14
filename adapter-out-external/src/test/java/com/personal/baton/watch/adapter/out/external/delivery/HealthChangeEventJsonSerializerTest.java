package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HealthChangeEventJsonSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HealthChangeEventJsonSerializer serializer =
            new HealthChangeEventJsonSerializer(objectMapper);

    @Test
    void writesExactlyTheEightAdoptedCallbackFields() throws Exception {
        HealthChangeEventPayload payload = payload(
                Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                42,
                Instant.parse("2026-08-02T01:02:03.456Z"));

        JsonNode json = objectMapper.readTree(serializer.apply(payload));

        assertEquals(8, json.size());
        assertEquals("00000000-0000-0000-0000-000000000001", json.required("eventId").stringValue());
        assertEquals("RESOURCE_HEALTH_CHANGED", json.required("eventType").stringValue());
        assertEquals("role-resource-123", json.required("resourceReference").stringValue());
        assertEquals(42, json.required("sourceRevision").longValue());
        assertEquals("00000000-0000-0000-0000-000000000003", json.required("attemptId").stringValue());
        assertEquals("DEGRADED", json.required("previousHealth").stringValue());
        assertEquals("BROKEN", json.required("currentHealth").stringValue());
        assertEquals("2026-08-02T01:02:03.456Z", json.required("changedAt").stringValue());
    }

    @Test
    void omitsAttemptIdEntirelyWhenTheHealthChangeDidNotComeFromAnAttempt() throws Exception {
        JsonNode json = objectMapper.readTree(
                serializer.apply(payload(Optional.empty(), 42, Instant.parse("2026-08-02T01:02:03Z"))));

        assertEquals(7, json.size());
        assertFalse(json.has("attemptId"));
    }

    @Test
    void preservesLongRevisionAndNanosecondUtcTimestampWithoutNumericCoercion() throws Exception {
        JsonNode json = objectMapper.readTree(
                serializer.apply(payload(
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
