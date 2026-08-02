package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HealthChangeEventJsonSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HealthChangeEventJsonSerializer serializer =
            new HealthChangeEventJsonSerializer(objectMapper);

    @Test
    void writesExactlyTheEightAdoptedCallbackFieldsInStableOrder() {
        HealthChangeEventPayload payload = payload(
                Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                42,
                Instant.parse("2026-08-02T01:02:03.456Z"));

        String json = new String(serializer.serialize(payload), StandardCharsets.UTF_8);

        assertEquals(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"eventType\":\"RESOURCE_HEALTH_CHANGED\","
                        + "\"resourceReference\":\"role-resource-123\","
                        + "\"sourceRevision\":42,"
                        + "\"attemptId\":\"00000000-0000-0000-0000-000000000003\","
                        + "\"previousHealth\":\"DEGRADED\","
                        + "\"currentHealth\":\"BROKEN\","
                        + "\"changedAt\":\"2026-08-02T01:02:03.456Z\"}",
                json);
        assertEquals(8, objectMapper.readTree(json).size());
        assertFalse(json.contains("leaseToken"));
        assertFalse(json.contains("deliveryAttempt"));
    }

    @Test
    void omitsAttemptIdEntirelyWhenTheHealthChangeDidNotComeFromAnAttempt() {
        String json = new String(
                serializer.serialize(payload(Optional.empty(), 42, Instant.parse("2026-08-02T01:02:03Z"))),
                StandardCharsets.UTF_8);

        assertEquals(
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"eventType\":\"RESOURCE_HEALTH_CHANGED\","
                        + "\"resourceReference\":\"role-resource-123\","
                        + "\"sourceRevision\":42,"
                        + "\"previousHealth\":\"DEGRADED\","
                        + "\"currentHealth\":\"BROKEN\","
                        + "\"changedAt\":\"2026-08-02T01:02:03Z\"}",
                json);
        assertEquals(7, objectMapper.readTree(json).size());
        assertFalse(json.contains("attemptId"));
    }

    @Test
    void preservesLongRevisionAndNanosecondUtcTimestampWithoutNumericCoercion() {
        String json = new String(
                serializer.serialize(payload(
                        Optional.empty(),
                        Long.MAX_VALUE,
                        Instant.parse("2026-08-02T01:02:03.123456789Z"))),
                StandardCharsets.UTF_8);

        assertTrue(json.contains("\"sourceRevision\":9223372036854775807"));
        assertTrue(json.contains("\"changedAt\":\"2026-08-02T01:02:03.123456789Z\""));
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
