package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import java.time.Instant;

public record MonitorResponse(
        String resourceReference,
        long sourceRevision,
        MonitoringState monitoringState,
        Health health,
        int consecutiveFailures,
        CheckOutcome lastOutcome,
        Instant lastCheckedAt,
        Instant nextCheckAt) {

    public static MonitorResponse from(MonitorProjection projection) {
        return new MonitorResponse(
                projection.resourceReference().value(),
                projection.sourceRevision().value(),
                projection.monitoringState(),
                projection.health(),
                projection.consecutiveFailures(),
                projection.lastOutcome().orElse(null),
                projection.lastCheckedAt().orElse(null),
                projection.nextCheckAt().orElse(null));
    }
}
