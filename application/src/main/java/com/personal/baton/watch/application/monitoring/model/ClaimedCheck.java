package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.Objects;
import java.util.UUID;

public record ClaimedCheck(
        UUID attemptId,
        UUID leaseToken,
        ResourceReference resourceReference,
        SourceRevision sourceRevision,
        TargetUrl targetUrl) {

    public ClaimedCheck {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(resourceReference, "resourceReference");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(targetUrl, "targetUrl");
    }
}
