package com.personal.baton.watch.domain.monitoring;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MonitorProjection(
        ResourceReference resourceReference,
        SourceRevision sourceRevision,
        MonitoringState monitoringState,
        HealthDerivation derivation,
        Optional<CheckOutcome> lastOutcome,
        Optional<Instant> lastCheckedAt,
        Optional<Instant> lastConclusiveAt,
        Optional<Instant> nextCheckAt,
        Optional<Instant> leaseExpiresAt) {

    public MonitorProjection {
        Objects.requireNonNull(resourceReference, "resourceReference");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(monitoringState, "monitoringState");
        Objects.requireNonNull(derivation, "derivation");
        Objects.requireNonNull(lastOutcome, "lastOutcome");
        Objects.requireNonNull(lastCheckedAt, "lastCheckedAt");
        Objects.requireNonNull(lastConclusiveAt, "lastConclusiveAt");
        Objects.requireNonNull(nextCheckAt, "nextCheckAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (monitoringState == MonitoringState.ACTIVE && nextCheckAt.isEmpty()) {
            throw new IllegalArgumentException("활성 점검 대상에는 다음 점검 시각이 필요합니다");
        }
        if (monitoringState == MonitoringState.INACTIVE && (nextCheckAt.isPresent() || leaseExpiresAt.isPresent())) {
            throw new IllegalArgumentException("비활성 점검 대상에는 일정이나 실행 중인 리스가 없어야 합니다");
        }
    }

    public CheckStatus checkStatusAt(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (monitoringState == MonitoringState.INACTIVE) {
            return CheckStatus.INACTIVE;
        }
        if (leaseExpiresAt.filter(expiresAt -> expiresAt.isAfter(observedAt)).isPresent()) {
            return CheckStatus.IN_PROGRESS;
        }
        return nextCheckAt.orElseThrow().isAfter(observedAt) ? CheckStatus.SCHEDULED : CheckStatus.QUEUED;
    }

    public Health health() {
        return derivation.health();
    }

    public int consecutiveFailures() {
        return derivation.consecutiveFailures();
    }
}
