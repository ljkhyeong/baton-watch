package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.in.RunDueChecksUseCase;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class RunDueChecksService implements RunDueChecksUseCase {

    private final CheckWorkPersistencePort persistence;
    private final UrlChecker checker;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration checkInterval;
    private final Duration internalFailureRetryInterval;
    private final int batchSize;

    public RunDueChecksService(
            CheckWorkPersistencePort persistence,
            UrlChecker checker,
            Clock clock,
            Duration leaseDuration,
            Duration checkInterval,
            Duration internalFailureRetryInterval,
            int batchSize) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = TimeBoundaryPolicy.requireSupportedOffset(leaseDuration, "leaseDuration");
        this.checkInterval = TimeBoundaryPolicy.requireSupportedOffset(checkInterval, "checkInterval");
        this.internalFailureRetryInterval = TimeBoundaryPolicy.requireSupportedOffset(
                internalFailureRetryInterval, "internalFailureRetryInterval");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public DueCheckBatchResult runDueChecks() {
        List<ClaimedCheck> claimedChecks = List.copyOf(
                persistence.claimDueChecks(leaseDuration, batchSize));
        if (claimedChecks.size() > batchSize) {
            throw new IllegalStateException("persistence returned more work than requested");
        }

        int applied = 0;
        int alreadyFinalized = 0;
        int staleClaims = 0;
        for (ClaimedCheck claimedCheck : claimedChecks) {
            CheckObservation observation = check(claimedCheck);
            Instant observedAt = clock.instant();
            Instant completedAt = observedAt.isBefore(claimedCheck.claimedAt())
                    ? claimedCheck.claimedAt()
                    : observedAt;
            Duration interval = observation.outcome() == CheckOutcome.INTERNAL_FAILURE
                    ? internalFailureRetryInterval
                    : checkInterval;
            CheckFinalization finalization = new CheckFinalization(
                    claimedCheck.attemptId(),
                    claimedCheck.leaseToken(),
                    observation,
                    completedAt,
                    TimeBoundaryPolicy.add(completedAt, interval, "check interval"));
            CheckFinalizationStatus status = persistence.finalizeCheck(finalization);
            switch (status) {
                case APPLIED -> applied++;
                case ALREADY_FINALIZED -> alreadyFinalized++;
                case STALE_CLAIM -> staleClaims++;
            }
        }
        return new DueCheckBatchResult(claimedChecks.size(), applied, alreadyFinalized, staleClaims);
    }

    private CheckObservation check(ClaimedCheck claimedCheck) {
        try {
            return Objects.requireNonNull(checker.check(claimedCheck.targetUrl()), "check observation");
        } catch (RuntimeException ignored) {
            return CheckObservation.internalFailure();
        }
    }

}
