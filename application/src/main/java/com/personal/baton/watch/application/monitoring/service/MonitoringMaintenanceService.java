package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.port.in.MonitoringMaintenanceUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.DatabaseClockPort;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class MonitoringMaintenanceService implements MonitoringMaintenanceUseCase {

    private final MonitorPersistencePort monitors;
    private final CheckWorkPersistencePort checkWork;
    private final DatabaseClockPort databaseClock;
    private final Clock clock;
    private final Duration staleAfter;
    private final Duration retention;
    private final int batchSize;

    public MonitoringMaintenanceService(
            MonitorPersistencePort monitors,
            CheckWorkPersistencePort checkWork,
            DatabaseClockPort databaseClock,
            Clock clock,
            Duration staleAfter,
            Duration retention,
            int batchSize) {
        this.monitors = monitors;
        this.checkWork = checkWork;
        this.databaseClock = databaseClock;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Override
    public int markStaleProjectionsUnknown() {
        Instant markedAt = clock.instant();
        return monitors.markStaleUnknown(markedAt.minus(staleAfter), markedAt, batchSize);
    }

    @Override
    public int purgeAttemptHistory() {
        return checkWork.purgeAttempts(clock.instant().minus(retention), batchSize);
    }

    @Override
    public Duration checkScheduleDelay() {
        return checkWork.getOldestDueCheckDelay();
    }

    @Override
    public Duration databaseClockOffset() {
        Instant before = clock.instant();
        Instant databaseTime = databaseClock.currentTime();
        Instant after = clock.instant();
        return clockOffset(before, databaseTime, after);
    }

    /** DB 조회 전후 시각의 중간점을 기준으로 DB 시각과의 차이를 계산한다. */
    static Duration clockOffset(Instant before, Instant databaseTime, Instant after) {
        Instant midpoint = before.plus(Duration.between(before, after).dividedBy(2));
        return Duration.between(databaseTime, midpoint);
    }
}
