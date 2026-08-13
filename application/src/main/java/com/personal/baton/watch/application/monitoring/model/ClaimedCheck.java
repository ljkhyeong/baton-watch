package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.Objects;
import java.util.UUID;

public record ClaimedCheck(
        UUID attemptId,
        UUID leaseToken,
        TargetUrl targetUrl) {

    public ClaimedCheck {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(targetUrl, "targetUrl");
    }
}
