package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.port.in.PurgeAttemptHistoryUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class PurgeAttemptHistoryService implements PurgeAttemptHistoryUseCase {

    private final CheckWorkPersistencePort persistence;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public PurgeAttemptHistoryService(
            CheckWorkPersistencePort persistence, Clock clock, Duration retention, int batchSize) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = requirePositive(retention);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int purgeAttemptHistory() {
        Instant completedBefore = clock.instant().minus(retention);
        int purged = persistence.purgeAttempts(completedBefore, batchSize);
        if (purged < 0 || purged > batchSize) {
            throw new IllegalStateException("persistence returned an invalid affected count");
        }
        return purged;
    }

    private Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "retention");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        return duration;
    }
}
