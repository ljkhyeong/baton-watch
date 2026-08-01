package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.util.Optional;

@FunctionalInterface
public interface GetMonitorProjectionUseCase {

    Optional<MonitorProjection> get(ResourceReference resourceReference);
}
