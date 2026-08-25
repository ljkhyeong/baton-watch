package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PersistencePropertiesTest {

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ZERO, Duration.ofSeconds(5), Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5), Duration.ZERO, Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ZERO));
    }

    @Test
    void rejectsTimeoutsThatCannotBeAppliedWithoutRounding() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofMillis(250)))
                .withMessageContaining("queryTimeout")
                .withMessageContaining("whole-second");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5), Duration.ofMillis(1_500), Duration.ofMillis(250)))
                .withMessageContaining("whole-second");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofNanos(1_500_000)))
                .withMessageContaining("whole-millisecond");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(31),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)))
                .withMessageContaining("between 1 and 30 seconds");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(31),
                        Duration.ofSeconds(1)))
                .withMessageContaining("between 1 and 30 seconds");
    }

    @Test
    void requiresLockTimeoutToBeShorterThanTransactionTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .withMessageContaining("shorter");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(
                        Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(6)))
                .withMessageContaining("shorter");
    }
}
