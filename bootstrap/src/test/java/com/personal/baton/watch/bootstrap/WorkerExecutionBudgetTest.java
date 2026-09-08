package com.personal.baton.watch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerExecutionBudgetTest {

    private static final Duration EXECUTION_BUDGET = Duration.ofSeconds(60);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);
    private static final DatabaseRuntimeProperties DATABASE = BootstrapTestFixtures.databaseRuntimeProperties();
    private static final PersistenceProperties PERSISTENCE = BootstrapTestFixtures.persistenceProperties();

    @Test
    void acceptsDefaultCheckAndDeliveryBudgets() {
        WorkerExecutionBudget.requireSafe(
                "check",
                EXECUTION_BUDGET,
                Duration.ofSeconds(30),
                TOTAL_TIMEOUT,
                1,
                DATABASE,
                PERSISTENCE);
        WorkerExecutionBudget.requireSafe(
                "event delivery",
                EXECUTION_BUDGET,
                Duration.ofSeconds(60),
                TOTAL_TIMEOUT,
                2,
                DATABASE,
                PERSISTENCE);
    }

    @Test
    void rejectsALeaseThatCannotCoverOneItem() {
        assertThrows(IllegalArgumentException.class, () -> WorkerExecutionBudget.requireSafe(
                "check",
                EXECUTION_BUDGET,
                Duration.ofSeconds(18),
                TOTAL_TIMEOUT,
                1,
                DATABASE,
                PERSISTENCE));
    }

    @Test
    void rejectsABatchThatCannotFinishWithinTheWorkerBudget() {
        assertThrows(IllegalArgumentException.class, () -> WorkerExecutionBudget.requireSafe(
                "event delivery",
                EXECUTION_BUDGET,
                Duration.ofSeconds(60),
                TOTAL_TIMEOUT,
                3,
                DATABASE,
                PERSISTENCE));
    }
}
