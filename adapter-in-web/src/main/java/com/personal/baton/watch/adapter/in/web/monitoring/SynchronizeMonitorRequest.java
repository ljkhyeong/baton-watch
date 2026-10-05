package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SynchronizeMonitorRequest(
        @NotNull @PositiveOrZero Long sourceRevision,
        @NotNull MonitoringState monitoringState,
        String targetUrl) {

    // 활성 상태만 URL을 가진다. 공개 메서드가 아니므로 Jackson은 이 검증용 getter를 속성으로 보지 않는다.
    @AssertTrue
    boolean isTargetUrlConsistentWithState() {
        return monitoringState == null || (monitoringState == MonitoringState.ACTIVE) == (targetUrl != null);
    }

    SynchronizeMonitorCommand toCommand(ResourceReference resourceReference) {
        SourceRevision revision = new SourceRevision(sourceRevision);
        if (monitoringState == MonitoringState.INACTIVE) {
            return SynchronizeMonitorCommand.inactive(resourceReference, revision);
        }
        try {
            return SynchronizeMonitorCommand.active(resourceReference, revision, new TargetUrl(targetUrl));
        } catch (IllegalArgumentException exception) {
            throw MonitorApiException.invalidTarget();
        }
    }
}
