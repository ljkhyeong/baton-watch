package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HealthDerivationTest {

    @Test
    void rejectsHealthAndFailureCountMismatches() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new HealthDerivation(Health.UNKNOWN, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new HealthDerivation(Health.HEALTHY, 1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new HealthDerivation(Health.DEGRADED, 0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new HealthDerivation(Health.DEGRADED, 3)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new HealthDerivation(Health.BROKEN, 2)));
    }

    @Test
    void unknownPreservesTheLastConsecutiveFailureCount() {
        HealthDerivation derivation = new HealthDerivation(Health.UNKNOWN, 2);

        assertEquals(2, derivation.consecutiveFailures());
    }
}
