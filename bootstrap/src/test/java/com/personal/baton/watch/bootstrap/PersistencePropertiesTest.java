package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PersistencePropertiesTest {

    @ParameterizedTest
    @CsvSource({
        // 0 이하 시간
        "PT0S,   PT5S,   PT0.001S,  queryTimeout must be a whole-second",
        "PT5S,   PT0S,   PT0.001S,  transactionTimeout must be a whole-second",
        "PT5S,   PT5S,   PT0S,      lockTimeout must be a positive whole-millisecond",
        // 반올림 없이 적용할 수 없는 시간
        "PT0.5S, PT5S,   PT0.25S,   queryTimeout must be a whole-second",
        "PT5S,   PT1.5S, PT0.25S,   transactionTimeout must be a whole-second",
        "PT5S,   PT5S,   PT0.0015S, lockTimeout must be a positive whole-millisecond",
        "PT31S,  PT5S,   PT1S,      queryTimeout must be a whole-second duration between 1 and 30 seconds",
        "PT5S,   PT31S,  PT1S,      transactionTimeout must be a whole-second duration between 1 and 30 seconds",
        // 잠금 대기는 트랜잭션 시간보다 짧아야 한다.
        "PT5S,   PT5S,   PT5S,      lockTimeout must be shorter than transactionTimeout",
        "PT5S,   PT5S,   PT6S,      lockTimeout must be shorter than transactionTimeout"
    })
    void rejectsUnsafeTimeouts(
            Duration queryTimeout, Duration transactionTimeout, Duration lockTimeout, String message) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceProperties(queryTimeout, transactionTimeout, lockTimeout))
                .withMessageContaining(message);
    }
}
