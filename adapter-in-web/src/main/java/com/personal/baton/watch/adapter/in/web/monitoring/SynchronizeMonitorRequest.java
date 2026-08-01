package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import jakarta.validation.constraints.NotNull;

public record SynchronizeMonitorRequest(
        @NotNull Long sourceRevision,
        @NotNull MonitoringState monitoringState,
        String targetUrl) {

    SynchronizeMonitorCommand toCommand(String rawResourceReference) {
        ResourceReference resourceReference;
        SourceRevision revision;
        try {
            resourceReference = new ResourceReference(rawResourceReference);
            revision = new SourceRevision(sourceRevision);
        } catch (IllegalArgumentException exception) {
            throw MonitorApiException.invalidRequest();
        }

        if (monitoringState == MonitoringState.ACTIVE) {
            if (targetUrl == null) {
                throw MonitorApiException.invalidRequest();
            }
            try {
                return SynchronizeMonitorCommand.active(resourceReference, revision, new TargetUrl(targetUrl));
            } catch (IllegalArgumentException exception) {
                throw MonitorApiException.invalidTarget();
            }
        }
        if (targetUrl != null) {
            throw MonitorApiException.invalidRequest();
        }
        return SynchronizeMonitorCommand.inactive(resourceReference, revision);
    }
}
