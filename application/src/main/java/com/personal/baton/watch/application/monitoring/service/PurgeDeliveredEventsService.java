package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.port.in.PurgeDeliveredEventsUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class PurgeDeliveredEventsService implements PurgeDeliveredEventsUseCase {

    private final HealthChangeEventDeliveryPersistencePort persistence;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public PurgeDeliveredEventsService(
            HealthChangeEventDeliveryPersistencePort persistence, Clock clock, Duration retention, int batchSize) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = requirePositive(retention);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int purgeDeliveredEvents() {
        Instant deliveredBefore = clock.instant().minus(retention);
        return persistence.purgeDeliveredEvents(deliveredBefore, batchSize);
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "retention");
        if (!duration.isPositive()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        return duration;
    }
}
