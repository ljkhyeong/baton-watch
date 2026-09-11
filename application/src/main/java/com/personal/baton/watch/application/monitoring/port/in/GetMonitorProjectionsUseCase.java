package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.util.List;

@FunctionalInterface
public interface GetMonitorProjectionsUseCase {

    List<MonitorProjection> get(List<ResourceReference> resourceReferences);
}
