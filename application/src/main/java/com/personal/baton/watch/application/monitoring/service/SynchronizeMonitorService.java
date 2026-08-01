package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import java.time.Clock;
import java.util.Objects;

public final class SynchronizeMonitorService implements SynchronizeMonitorUseCase {

    private final MonitorPersistencePort persistence;
    private final Clock clock;

    public SynchronizeMonitorService(MonitorPersistencePort persistence, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SynchronizationResult synchronize(SynchronizeMonitorCommand command) {
        Objects.requireNonNull(command, "command");
        return persistence.synchronize(command, clock.instant());
    }
}
