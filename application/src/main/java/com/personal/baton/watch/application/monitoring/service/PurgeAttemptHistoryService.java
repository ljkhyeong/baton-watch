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
        this.retention = TimeBoundaryPolicy.requireSupportedOffset(retention, "retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int purgeAttemptHistory() {
        Instant completedBefore = clock.instant().minus(retention);
        return persistence.purgeAttempts(completedBefore, batchSize);
    }
}
