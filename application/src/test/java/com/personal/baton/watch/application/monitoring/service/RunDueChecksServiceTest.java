package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunDueChecksServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Duration INTERNAL_RETRY = Duration.ofSeconds(30);
    private static final ClaimedCheck CLAIM = new ClaimedCheck(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            new ResourceReference("resource-1"),
            new SourceRevision(11),
            new TargetUrl("https://example.com/health"));

    @Test
    void claimsThenChecksThenFinalizesOutsideTheClaimOperation() {
        List<String> calls = new ArrayList<>();
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(calls);
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> {
                    calls.add("check");
                    return CheckObservation.forHttpStatus(204);
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                5);

        DueCheckBatchResult result = service.runDueChecks();

        assertEquals(List.of("claim", "check", "finalize"), calls);
        assertEquals(new DueCheckBatchResult(1, 1, 0, 0), result);
        assertEquals(NOW, persistence.claimedAt);
        assertEquals(NOW.plus(LEASE), persistence.leaseUntil);
        assertEquals(5, persistence.limit);
        assertEquals(CheckOutcome.SUCCESS, persistence.finalization.observation().outcome());
        assertEquals(NOW.plus(INTERVAL), persistence.finalization.nextCheckAt());
    }

    @Test
    void convertsUnexpectedCheckerRuntimeErrorsToSafeInternalFailures() {
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(new ArrayList<>());
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> {
                    throw new IllegalStateException("secret exception detail");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                1);

        service.runDueChecks();

        assertEquals(CheckObservation.internalFailure(), persistence.finalization.observation());
        assertEquals(NOW.plus(INTERNAL_RETRY), persistence.finalization.nextCheckAt());
    }

    private static final class RecordingWorkPersistence implements CheckWorkPersistencePort {

        private final List<String> calls;
        private Instant claimedAt;
        private Instant leaseUntil;
        private int limit;
        private CheckFinalization finalization;

        private RecordingWorkPersistence(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<ClaimedCheck> claimDueChecks(Instant claimedAt, Instant leaseUntil, int limit) {
            calls.add("claim");
            this.claimedAt = claimedAt;
            this.leaseUntil = leaseUntil;
            this.limit = limit;
            return List.of(CLAIM);
        }

        @Override
        public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
            calls.add("finalize");
            this.finalization = finalization;
            return CheckFinalizationStatus.APPLIED;
        }

        @Override
        public int purgeAttempts(Instant completedBefore, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
