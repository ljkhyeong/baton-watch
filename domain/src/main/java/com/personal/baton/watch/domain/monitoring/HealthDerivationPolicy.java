package com.personal.baton.watch.domain.monitoring;

import java.util.Objects;

public final class HealthDerivationPolicy {

    public HealthDerivation derive(HealthDerivation current, CheckOutcome outcome) {
        Objects.requireNonNull(current, "current");

        if (!outcome.isConclusive()) {
            return current;
        }
        if (outcome == CheckOutcome.SUCCESS) {
            return new HealthDerivation(Health.HEALTHY, 0);
        }

        int failures = Math.clamp(
                (long) current.consecutiveFailures() + 1,
                1,
                Integer.MAX_VALUE);
        Health health = failures >= 3 ? Health.BROKEN : Health.DEGRADED;
        return new HealthDerivation(health, failures);
    }

    public HealthDerivation markStale(HealthDerivation current) {
        return new HealthDerivation(Health.UNKNOWN, current.consecutiveFailures());
    }
}
