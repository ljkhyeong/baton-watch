package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.port.in.GetEventDeliveryBacklogUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class GetEventDeliveryBacklogService implements GetEventDeliveryBacklogUseCase {

    private final HealthChangeEventDeliveryPersistencePort persistence;
    private final Clock clock;

    public GetEventDeliveryBacklogService(HealthChangeEventDeliveryPersistencePort persistence, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EventDeliveryBacklog getEventDeliveryBacklog() {
        EventDeliveryBacklogSnapshot snapshot = persistence.getBacklogSnapshot();
        Instant observedAt = clock.instant();
        Optional<Duration> oldestAge = snapshot.oldestChangedAt()
                .map(changedAt -> changedAt.isAfter(observedAt)
                        ? Duration.ZERO
                        : Duration.between(changedAt, observedAt));
        return new EventDeliveryBacklog(snapshot.pendingCount(), oldestAge);
    }
}
