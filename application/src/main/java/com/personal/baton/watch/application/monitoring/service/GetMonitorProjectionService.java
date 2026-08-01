package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.util.Objects;
import java.util.Optional;

public final class GetMonitorProjectionService implements GetMonitorProjectionUseCase {

    private final MonitorPersistencePort persistence;

    public GetMonitorProjectionService(MonitorPersistencePort persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    @Override
    public Optional<MonitorProjection> get(ResourceReference resourceReference) {
        return persistence.findProjection(Objects.requireNonNull(resourceReference, "resourceReference"));
    }
}
