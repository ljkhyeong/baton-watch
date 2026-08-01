package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Instant;
import java.util.Optional;

public interface MonitorPersistencePort {

    SynchronizationResult synchronize(SynchronizeMonitorCommand command, Instant synchronizedAt);

    Optional<MonitorProjection> findProjection(ResourceReference resourceReference);

    int markStaleUnknown(Instant staleBefore, Instant markedAt, int limit);
}
