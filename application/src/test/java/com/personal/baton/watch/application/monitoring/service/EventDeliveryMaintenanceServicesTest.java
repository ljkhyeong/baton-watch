package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventDeliveryMaintenanceServicesTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void deliveredRetentionUsesFixedClockAndBoundedBatch() {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence();
        persistence.purgedEvents = 3;
        PurgeDeliveredEventsService service = new PurgeDeliveredEventsService(
                persistence, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30), 50);

        assertEquals(3, service.purgeDeliveredEvents());
        assertEquals(NOW.minus(Duration.ofDays(30)), persistence.deliveredBefore);
        assertEquals(50, persistence.limit);
    }

    @Test
    void backlogReportsPendingCountAndOldestChangedAge() {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence();
        persistence.backlogSnapshot = new EventDeliveryBacklogSnapshot(
                4, Optional.of(NOW.minus(Duration.ofMinutes(7))));
        GetEventDeliveryBacklogService service = new GetEventDeliveryBacklogService(
                persistence, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(
                new EventDeliveryBacklog(4, Optional.of(Duration.ofMinutes(7))),
                service.getEventDeliveryBacklog());

        persistence.backlogSnapshot = new EventDeliveryBacklogSnapshot(0, Optional.empty());
        assertEquals(new EventDeliveryBacklog(0, Optional.empty()), service.getEventDeliveryBacklog());
    }

    @Test
    void backlogClampsAClockSkewedFutureEventAgeToZero() {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence();
        persistence.backlogSnapshot = new EventDeliveryBacklogSnapshot(
                1, Optional.of(NOW.plusSeconds(30)));
        GetEventDeliveryBacklogService service = new GetEventDeliveryBacklogService(
                persistence, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(
                new EventDeliveryBacklog(1, Optional.of(Duration.ZERO)),
                service.getEventDeliveryBacklog());
    }
}
