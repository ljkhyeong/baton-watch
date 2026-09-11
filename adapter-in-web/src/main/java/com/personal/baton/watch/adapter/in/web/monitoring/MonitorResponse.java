package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.CheckStatus;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import java.time.Instant;

public record MonitorResponse(
        String resourceReference,
        long sourceRevision,
        MonitoringState monitoringState,
        CheckStatus checkStatus,
        Health health,
        int consecutiveFailures,
        CheckOutcome lastOutcome,
        Instant lastCheckedAt,
        Instant lastConclusiveAt,
        Instant nextCheckAt) {

    public static MonitorResponse from(MonitorProjection projection, Instant observedAt) {
        return new MonitorResponse(
                projection.resourceReference().value(),
                projection.sourceRevision().value(),
                projection.monitoringState(),
                projection.checkStatusAt(observedAt),
                projection.health(),
                projection.consecutiveFailures(),
                projection.lastOutcome().orElse(null),
                projection.lastCheckedAt().orElse(null),
                projection.lastConclusiveAt().orElse(null),
                projection.nextCheckAt().orElse(null));
    }
}
