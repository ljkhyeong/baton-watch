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
        EventDeliveryMaintenanceService service = service(persistence);

        assertEquals(3, service.purgeDeliveredEvents());
        assertEquals(NOW.minus(Duration.ofDays(30)), persistence.deliveredBefore);
        assertEquals(50, persistence.limit);
    }

    @Test
    void backlogReportsPendingCountAndOldestChangedAge() {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence();
        persistence.backlogSnapshot = new EventDeliveryBacklogSnapshot(
                4, Optional.of(NOW.minus(Duration.ofMinutes(7))));
        EventDeliveryMaintenanceService service = service(persistence);

        assertEquals(
                new EventDeliveryBacklog(4, Duration.ofMinutes(7)),
                service.eventDeliveryBacklog());

        persistence.backlogSnapshot = new EventDeliveryBacklogSnapshot(0, Optional.empty());
        assertEquals(new EventDeliveryBacklog(0, Duration.ZERO), service.eventDeliveryBacklog());
    }

    @Test
    void backlogClampsAClockSkewedFutureEventAgeToZero() {
        RecordingEventDeliveryPersistence persistence = new RecordingEventDeliveryPersistence();
        persistence.backlogSnapshot = new EventDeliveryBacklogSnapshot(
                1, Optional.of(NOW.plusSeconds(30)));
        EventDeliveryMaintenanceService service = service(persistence);

        assertEquals(
                new EventDeliveryBacklog(1, Duration.ZERO),
                service.eventDeliveryBacklog());
    }

    private static EventDeliveryMaintenanceService service(RecordingEventDeliveryPersistence persistence) {
        return new EventDeliveryMaintenanceService(
                persistence, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30), 50);
    }
}
