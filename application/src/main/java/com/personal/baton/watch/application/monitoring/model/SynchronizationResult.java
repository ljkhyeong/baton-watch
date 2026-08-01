package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import java.util.Objects;

public record SynchronizationResult(SynchronizationStatus status, MonitorProjection projection) {

    public SynchronizationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(projection, "projection");
    }
}
