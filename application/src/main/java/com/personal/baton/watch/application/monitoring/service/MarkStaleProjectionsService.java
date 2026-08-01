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
        this.stalenessThreshold = requirePositive(stalenessThreshold);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int markStaleProjectionsUnknown() {
        Instant markedAt = clock.instant();
        int changed = persistence.markStaleUnknown(markedAt.minus(stalenessThreshold), markedAt, batchSize);
        return validateAffectedCount(changed);
    }

    private Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "stalenessThreshold");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("stalenessThreshold must be positive");
        }
        return duration;
    }

    private int validateAffectedCount(int changed) {
        if (changed < 0 || changed > batchSize) {
            throw new IllegalStateException("persistence returned an invalid affected count");
        }
        return changed;
    }
}
