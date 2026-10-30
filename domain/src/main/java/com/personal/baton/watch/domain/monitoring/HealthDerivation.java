package com.personal.baton.watch.domain.monitoring;

import java.util.Objects;

public record HealthDerivation(Health health, int consecutiveFailures) {

    private static final int BROKEN_FAILURES = 3;

    public HealthDerivation {
        Objects.requireNonNull(health, "health");
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutive failures must be non-negative");
        }
        if (health == Health.HEALTHY && consecutiveFailures != 0) {
            throw new IllegalArgumentException("healthy state cannot have consecutive failures");
        }
        if (health == Health.DEGRADED && (consecutiveFailures == 0 || consecutiveFailures >= BROKEN_FAILURES)) {
            throw new IllegalArgumentException("degraded state requires one or two consecutive failures");
        }
        if (health == Health.BROKEN && consecutiveFailures < BROKEN_FAILURES) {
            throw new IllegalArgumentException("broken state requires at least three consecutive failures");
        }
    }

    /** 점검 결과를 반영한 상태다. 확정되지 않은 결과는 현재 상태를 유지한다. */
    public HealthDerivation after(CheckOutcome outcome) {
        if (!outcome.isConclusive()) {
            return this;
        }
        if (outcome == CheckOutcome.SUCCESS) {
            return new HealthDerivation(Health.HEALTHY, 0);
        }

        int failures = Math.clamp((long) consecutiveFailures + 1, 1, Integer.MAX_VALUE);
        Health next = failures >= BROKEN_FAILURES ? Health.BROKEN : Health.DEGRADED;
        return new HealthDerivation(next, failures);
    }

    /** 결과가 오래되어 상태를 알 수 없게 된 경우다. 연속 실패 수는 유지한다. */
    public HealthDerivation stale() {
        return new HealthDerivation(Health.UNKNOWN, consecutiveFailures);
    }
}
