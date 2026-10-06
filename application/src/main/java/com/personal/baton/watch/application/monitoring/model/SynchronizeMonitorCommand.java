package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.Objects;
import java.util.Optional;

public record SynchronizeMonitorCommand(
        ResourceReference resourceReference,
        SourceRevision sourceRevision,
        MonitoringState monitoringState,
        Optional<TargetUrl> targetUrl) {

    public SynchronizeMonitorCommand {
        Objects.requireNonNull(resourceReference, "resourceReference");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(monitoringState, "monitoringState");
        Objects.requireNonNull(targetUrl, "targetUrl");
        if (monitoringState == MonitoringState.ACTIVE && targetUrl.isEmpty()) {
            throw new IllegalArgumentException("active monitor requires a target URL");
        }
        if (monitoringState == MonitoringState.INACTIVE && targetUrl.isPresent()) {
            throw new IllegalArgumentException("inactive monitor cannot have a target URL");
        }
    }

    public static SynchronizeMonitorCommand active(
            ResourceReference resourceReference, SourceRevision sourceRevision, TargetUrl targetUrl) {
        return new SynchronizeMonitorCommand(
                resourceReference, sourceRevision, MonitoringState.ACTIVE, Optional.of(targetUrl));
    }

    public static SynchronizeMonitorCommand inactive(
            ResourceReference resourceReference, SourceRevision sourceRevision) {
        return new SynchronizeMonitorCommand(
                resourceReference, sourceRevision, MonitoringState.INACTIVE, Optional.empty());
    }
}
