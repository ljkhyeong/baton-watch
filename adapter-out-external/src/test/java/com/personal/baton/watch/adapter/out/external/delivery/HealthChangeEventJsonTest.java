package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HealthChangeEventJsonTest {

    private final HealthChangeEventJson serializer = new HealthChangeEventJson();

    @Test
    void writesOnlyTheAdoptedCallbackFieldsInStableOrder() {
        ClaimedHealthChangeEvent event = event(Optional.of(
                UUID.fromString("00000000-0000-0000-0000-000000000003")));

        String json = new String(serializer.serialize(event), StandardCharsets.UTF_8);

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
        assertFalse(json.contains("leaseToken"));
        assertFalse(json.contains("deliveryAttempt"));
    }

    @Test
    void omitsAttemptIdWhenTheHealthChangeDidNotComeFromAnAttempt() {
        String json = new String(serializer.serialize(event(Optional.empty())), StandardCharsets.UTF_8);

        assertFalse(json.contains("attemptId"));
    }

    private static ClaimedHealthChangeEvent event(Optional<UUID> attemptId) {
        return new ClaimedHealthChangeEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ResourceReference("role-resource-123"),
                new SourceRevision(42),
                attemptId,
                Health.DEGRADED,
                Health.BROKEN,
                Instant.parse("2026-08-02T01:02:03.456Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                7);
    }
}
