package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.port.in.EventDeliveryMaintenanceUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class EventDeliveryMaintenanceService implements EventDeliveryMaintenanceUseCase {

    private final HealthChangeEventDeliveryPersistencePort persistence;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public EventDeliveryMaintenanceService(
            HealthChangeEventDeliveryPersistencePort persistence, Clock clock, Duration retention, int batchSize) {
        this.persistence = persistence;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Override
    public int purgeDeliveredEvents() {
        return persistence.purgeDeliveredEvents(clock.instant().minus(retention), batchSize);
    }

    @Override
    public EventDeliveryBacklog eventDeliveryBacklog() {
        EventDeliveryBacklogSnapshot snapshot = persistence.getBacklogSnapshot();
        Instant observedAt = clock.instant();
        // DB와 애플리케이션 시계 차이로 변경 시각이 미래이면 0으로 둔다.
        Duration oldestAge = snapshot.oldestChangedAt()
                .map(changedAt -> Duration.between(changedAt, observedAt))
                .filter(Duration::isPositive)
                .orElse(Duration.ZERO);
        return new EventDeliveryBacklog(snapshot.pendingCount(), oldestAge);
    }
}
