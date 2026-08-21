package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.port.in.MarkStaleProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class MarkStaleProjectionsService implements MarkStaleProjectionsUseCase {

    private final MonitorPersistencePort persistence;
    private final Clock clock;
    private final Duration stalenessThreshold;
    private final int batchSize;

    public MarkStaleProjectionsService(
            MonitorPersistencePort persistence, Clock clock, Duration stalenessThreshold, int batchSize) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stalenessThreshold = TimeBoundaryPolicy.requireSupportedOffset(
                stalenessThreshold, "stalenessThreshold");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int markStaleProjectionsUnknown() {
        Instant markedAt = clock.instant();
        return persistence.markStaleUnknown(
                markedAt.minus(stalenessThreshold), markedAt, batchSize);
    }
}
