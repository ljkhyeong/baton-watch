package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthDerivationPolicyTest {

    private final HealthDerivationPolicy policy = new HealthDerivationPolicy();

    @Test
    void successMakesHealthHealthyAndResetsFailures() {
        HealthDerivation result = policy.derive(new HealthDerivation(Health.BROKEN, 4), CheckOutcome.SUCCESS);

        assertEquals(new HealthDerivation(Health.HEALTHY, 0), result);
    }

    @Test
    void conclusiveFailuresBecomeDegradedThenBroken() {
        HealthDerivation first = policy.derive(HealthDerivation.unknown(), CheckOutcome.CONNECT_TIMEOUT);
        HealthDerivation second = policy.derive(first, CheckOutcome.HTTP_SERVER_ERROR);
        HealthDerivation third = policy.derive(second, CheckOutcome.DNS_FAILURE);

        assertEquals(new HealthDerivation(Health.DEGRADED, 1), first);
        assertEquals(new HealthDerivation(Health.DEGRADED, 2), second);
        assertEquals(new HealthDerivation(Health.BROKEN, 3), third);
    }

    @Test
    void internalFailureDoesNotChangeHealthAndStalenessMakesItUnknown() {
        HealthDerivation current = new HealthDerivation(Health.DEGRADED, 2);

        assertEquals(current, policy.derive(current, CheckOutcome.INTERNAL_FAILURE));
        assertEquals(new HealthDerivation(Health.UNKNOWN, 2), policy.markStale(current));
    }
}
