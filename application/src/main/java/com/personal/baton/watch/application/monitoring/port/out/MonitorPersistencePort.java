package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

public interface MonitorPersistencePort {

    SynchronizationResult synchronize(SynchronizeMonitorCommand command, Instant synchronizedAt);

    List<MonitorProjection> findProjections(List<ResourceReference> resourceReferences);

    MonitorCheckRequestResult requestCheck(
            ResourceReference resourceReference, Instant requestedAt, Duration minimumInterval);

    int markStaleUnknown(Instant staleBefore, Instant markedAt, int limit);
}
