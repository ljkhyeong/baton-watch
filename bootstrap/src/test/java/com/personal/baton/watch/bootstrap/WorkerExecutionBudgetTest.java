package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerExecutionBudgetTest {

    @Test
    void acceptsDefaultCheckAndDeliveryBudgets() {
        budget("check", 30, 1);
        budget("event delivery", 60, 2);
    }

    @Test
    void rejectsALeaseThatCannotCoverOneItem() {
        assertThrows(IllegalArgumentException.class, () -> budget("check", 18, 1));
    }

    @Test
    void rejectsABatchThatCannotFinishWithinTheWorkerBudget() {
        assertThrows(IllegalArgumentException.class, () -> budget("event delivery", 60, 3));
    }

    private static void budget(String worker, long leaseSeconds, int batchSize) {
        WorkerExecutionBudget.requireSafe(
                worker,
                Duration.ofSeconds(60),
                Duration.ofSeconds(leaseSeconds),
                Duration.ofSeconds(5),
                batchSize,
                BootstrapTestFixtures.databaseRuntimeProperties(),
                BootstrapTestFixtures.persistenceProperties());
    }
}
